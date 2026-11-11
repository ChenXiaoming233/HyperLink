package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class SafeNotificationRuleWriter {
    private static final Object PROCESS_WRITE_LOCK = new Object();

    private final RuleStoreGateway gateway;
    private final LastKnownGoodStore lkgStore;
    private final Supplier<String> ruleIdSupplier;
    private RuleLkg lastLkg;

    public SafeNotificationRuleWriter(
            RuleStoreGateway gateway, LastKnownGoodStore lkgStore) {
        this(gateway, lkgStore, () -> UUID.randomUUID().toString());
    }

    SafeNotificationRuleWriter(
            RuleStoreGateway gateway,
            LastKnownGoodStore lkgStore,
            Supplier<String> ruleIdSupplier) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.lkgStore = Objects.requireNonNull(lkgStore, "lkgStore");
        this.ruleIdSupplier = Objects.requireNonNull(ruleIdSupplier, "ruleIdSupplier");
    }

    public ServiceResult<RuleCreationResult> createAllNotificationsRule(
            ConfirmedTargetSelection selection) {
        synchronized (PROCESS_WRITE_LOCK) {
            return createAllNotificationsRuleLocked(selection);
        }
    }

    public ServiceResult<RuleCreationResult> saveStructuredRule(
            ConfirmedTargetSelection selection, StructuredRuleDraft draft) {
        synchronized (PROCESS_WRITE_LOCK) {
            return saveStructuredRuleLocked(selection, draft);
        }
    }

    public ServiceResult<RuleMutationResult> changeRuleStatus(
            String ruleId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized) {
        if (!isLifecycleStatus(expectedStatus)
                || !isLifecycleStatus(nextStatus)
                || expectedStatus.equals(nextStatus)) {
            return ServiceResult.failure("仅支持 active 与 paused 之间的规则状态变更。");
        }
        synchronized (PROCESS_WRITE_LOCK) {
            return mutateRuleLocked(
                    ruleId,
                    expectedStatus,
                    nextStatus,
                    expectedTargetAuthorized,
                    false);
        }
    }

    public ServiceResult<RuleMutationResult> deleteRule(
            String ruleId, boolean expectedTargetAuthorized) {
        synchronized (PROCESS_WRITE_LOCK) {
            return mutateRuleLocked(
                    ruleId, "", "", expectedTargetAuthorized, true);
        }
    }

    private ServiceResult<RuleCreationResult> createAllNotificationsRuleLocked(
            ConfirmedTargetSelection selection) {
        return writeRuleLocked(
                selection,
                (source, target, rules) -> createRule(source, target, rules));
    }

    private ServiceResult<RuleCreationResult> saveStructuredRuleLocked(
            ConfirmedTargetSelection selection, StructuredRuleDraft draft) {
        if (draft == null) {
            return ServiceResult.failure("未提供结构化规则，未执行任何写入。");
        }
        return writeRuleLocked(
                selection,
                (source, target, rules) ->
                        createStructuredRule(source, target, rules, draft));
    }

    private ServiceResult<RuleCreationResult> writeRuleLocked(
            ConfirmedTargetSelection selection, RuleFactory ruleFactory) {
        if (selection == null) {
            return ServiceResult.failure("未确认目标，未执行任何写入。");
        }
        String persistentFault = readOnlyFaultMessageLocked();
        if (!persistentFault.isEmpty()) {
            return ServiceResult.failure(persistentFault);
        }

        ServiceResult<RuleStoreSnapshot> readResult;
        try {
            readResult = gateway.read();
        } catch (RuntimeException exception) {
            return ServiceResult.failure("规则快照读取异常，未执行任何写入。");
        }
        if (!readResult.isSuccess()) {
            return ServiceResult.failure("无法读取完整规则快照，未执行任何写入。");
        }
        RuleStoreSnapshot current = readResult.value().orElseThrow();
        if (!current.readOnlyFaultMessage().isEmpty()) {
            return ServiceResult.failure(current.readOnlyFaultMessage());
        }
        long expectedStoreVersion = current.storeVersion();
        List<NotificationRule> expectedRules = current.rules();
        NotificationFlowSnapshot flow = current.flowSnapshot();
        if (flow.staleCache()
                || (flow.connectionState() != ConnectionState.CONNECTED
                        && flow.connectionState()
                                != ConnectionState.COMPATIBILITY_UNVERIFIED)) {
            return ServiceResult.failure("当前规则快照不可用于写入。");
        }

        CapabilityReport capability = CapabilityProbe.evaluate(current.capabilities());
        if (!capability.passed()) {
            return ServiceResult.failure(capability.readOnlyMessage());
        }
        String snapshotIssue = RuleSnapshotValidator.issue(current);
        if (!snapshotIssue.isEmpty()) {
            return enterReadOnlyFaultLocked(snapshotIssue);
        }
        if (!selection.source().deviceId().equals(current.sourceDevice().deviceId())) {
            return ServiceResult.failure("来源设备与当前规则快照不一致，未执行任何写入。");
        }

        TargetDevice target = findTarget(flow, selection.target().deviceId());
        if (target == null || !target.supportsNotificationReceive()) {
            return ServiceResult.failure("目标能力已变化，请重新读取并选择目标。");
        }
        if (!sameTargetState(selection.target(), target)) {
            return ServiceResult.failure("目标状态已变化，请重新读取并确认目标。");
        }

        RuleLkg lkg = RuleLkg.capture(current);
        boolean lkgSaved;
        try {
            lkgSaved = lkgStore.save(lkg);
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法保存完整 LKG，未执行任何写入。");
        }
        if (!lkgSaved) {
            return ServiceResult.failure("无法保存完整 LKG，未执行任何写入。");
        }
        lastLkg = lkg;

        NotificationRule createdRule;
        List<NotificationRule> candidate;
        String expectedSerialized;
        try {
            createdRule =
                    ruleFactory.create(
                            current.sourceDevice(), target, current.rules());
            candidate = upsert(current.rules(), createdRule);
            expectedSerialized = RuleSerialization.serialize(candidate);
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法生成有效规则，未执行任何写入。");
        }

        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult = gateway.write(candidate, expectedStoreVersion, expectedRules);
        } catch (RuntimeException exception) {
            return failAfterWriteAttempt(
                    lkg, current, "规则写入异常，");
        }
        if (!writeResult.isSuccess()) {
            return failAfterWriteAttempt(
                    lkg, current, "规则写入未完成，");
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return failAfterCommittedReadFailure(
                    lkg, receipt, "写入后回读异常，");
        }
        if (!readBack.isSuccess()) {
            return failAfterCommittedReadFailure(
                    lkg, receipt, "写入后无法回读规则，");
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        if (!verified.readOnlyFaultMessage().isEmpty()
                || verified.storeVersion() != receipt.storeVersion()
                || hasUnsafeStructure(verified)
                || !lkg.targetStatesMatch(verified)
                || !lkg.permissionsMatch(verified)
                || !receipt.serializedJson().equals(expectedSerialized)
                || !verified.serializedJson().equals(expectedSerialized)
                || !matchesRules(candidate, verified.rules())
                || !uniqueRuleIds(candidate)
                || matchingTargetCount(candidate, createdRule) != 1
                || !targetRuleMatches(candidate, createdRule)) {
            return failAfterCommittedWrite(
                    lkg, receipt, verified, "写入后校验失败，");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return failAfterCommittedWrite(
                    lkg, receipt, verified, "写入返回前只读故障态已变化，");
        }

        List<String> notes = new ArrayList<>();
        notes.add(systemGateNotice(flow.notificationAccess()));
        notes.add(targetCapabilityNotice(target));
        if (!selection.offlineWarning().isEmpty()) {
            notes.add(selection.offlineWarning());
        }
        boolean compatibilityUnverified =
                capability.compatibilityUnverified() || flow.compatibilityUnverified();
        if (compatibilityUnverified) {
            notes.add("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return ServiceResult.success(
                new RuleCreationResult(
                        createdRule, lkg, compatibilityUnverified, notes));
    }

    public boolean isReadOnlyFault() {
        synchronized (PROCESS_WRITE_LOCK) {
            return !readOnlyFaultMessageLocked().isEmpty();
        }
    }

    public String readOnlyFaultMessage() {
        synchronized (PROCESS_WRITE_LOCK) {
            return readOnlyFaultMessageLocked();
        }
    }

    public RuleLkg lastKnownGood() {
        synchronized (PROCESS_WRITE_LOCK) {
            return lastLkg;
        }
    }

    public void enterReadOnlyFault(String message) {
        synchronized (PROCESS_WRITE_LOCK) {
            gateway.enterReadOnlyFault(Objects.requireNonNull(message, "message"));
        }
    }

    private ServiceResult<RuleMutationResult> mutateRuleLocked(
            String ruleId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized,
            boolean delete) {
        if (ruleId == null || ruleId.trim().isEmpty()) {
            return ServiceResult.failure("未指定要修改的规则，未执行任何写入。");
        }
        String persistentFault = readOnlyFaultMessageLocked();
        if (!persistentFault.isEmpty()) {
            return ServiceResult.failure(persistentFault);
        }

        ServiceResult<RuleStoreSnapshot> readResult;
        try {
            readResult = gateway.read();
        } catch (RuntimeException exception) {
            return ServiceResult.failure("规则快照读取异常，未执行任何写入。");
        }
        if (!readResult.isSuccess()) {
            return ServiceResult.failure("无法读取完整规则快照，未执行任何写入。");
        }
        RuleStoreSnapshot current = readResult.value().orElseThrow();
        if (!current.readOnlyFaultMessage().isEmpty()) {
            return ServiceResult.failure(current.readOnlyFaultMessage());
        }
        NotificationFlowSnapshot flow = current.flowSnapshot();
        if (flow.staleCache()
                || (flow.connectionState() != ConnectionState.CONNECTED
                        && flow.connectionState()
                                != ConnectionState.COMPATIBILITY_UNVERIFIED)) {
            return ServiceResult.failure("当前规则快照不可用于写入。");
        }
        CapabilityReport capability = CapabilityProbe.evaluate(current.capabilities());
        if (!capability.passed()) {
            return ServiceResult.failure(capability.readOnlyMessage());
        }
        String snapshotIssue = RuleSnapshotValidator.issue(current);
        if (!snapshotIssue.isEmpty()) {
            return enterReadOnlyFaultLocked(snapshotIssue);
        }

        int ruleIndex = findRuleIndex(current.rules(), ruleId);
        if (ruleIndex < 0) {
            return ServiceResult.failure("规则不存在或已被外部修改，请重新读取。");
        }
        NotificationRule currentRule = current.rules().get(ruleIndex);
        TargetDevice target = findTarget(flow, currentRule.targetDeviceId());
        if (target == null) {
            return ServiceResult.failure("规则目标不在当前快照中，未执行任何写入。");
        }
        if (target.targetAuthorized() != expectedTargetAuthorized) {
            return ServiceResult.failure(
                    "目标授权已在外部变化，未执行任何写入；不会自动重新授权或覆盖外部变化。");
        }
        if (!delete && !expectedStatus.equals(currentRule.status())) {
            return ServiceResult.failure("规则状态已被外部修改，未执行任何写入。");
        }

        RuleLkg lkg = RuleLkg.capture(current);
        boolean lkgSaved;
        try {
            lkgSaved = lkgStore.save(lkg);
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法保存完整 LKG，未执行任何写入。");
        }
        if (!lkgSaved) {
            return ServiceResult.failure("无法保存完整 LKG，未执行任何写入。");
        }
        lastLkg = lkg;

        NotificationRule changedRule = null;
        List<NotificationRule> candidate = new ArrayList<>(current.rules());
        if (delete) {
            candidate.remove(ruleIndex);
        } else {
            changedRule =
                    new NotificationRule(
                            currentRule.ruleId(),
                            currentRule.sourceDeviceId(),
                            currentRule.sourceDeviceName(),
                            currentRule.targetDeviceId(),
                            currentRule.targetDeviceName(),
                            nextStatus,
                            currentRule.summary(),
                            currentRule.dslJson(),
                            currentRule.createdAt());
            candidate.set(ruleIndex, changedRule);
        }
        String expectedSerialized;
        try {
            expectedSerialized = RuleSerialization.serialize(candidate);
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法生成有效规则，未执行任何写入。");
        }

        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult =
                    gateway.write(
                            candidate, current.storeVersion(), current.rules());
        } catch (RuntimeException exception) {
            return failAfterWriteAttempt(
                    lkg, current, "规则变更写入异常，");
        }
        if (!writeResult.isSuccess()) {
            return failAfterWriteAttempt(
                    lkg, current, "规则变更写入未完成，");
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return failAfterCommittedReadFailure(
                    lkg, receipt, "规则变更后回读异常，");
        }
        if (!readBack.isSuccess()) {
            return failAfterCommittedReadFailure(
                    lkg, receipt, "规则变更后无法回读规则，");
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        if (!verified.readOnlyFaultMessage().isEmpty()
                || verified.storeVersion() != receipt.storeVersion()
                || hasUnsafeStructure(verified)
                || !lkg.targetStatesMatch(verified)
                || !lkg.permissionsMatch(verified)
                || !receipt.serializedJson().equals(expectedSerialized)
                || !verified.serializedJson().equals(expectedSerialized)
                || !matchesRules(candidate, verified.rules())
                || !uniqueRuleIds(candidate)) {
            return failAfterCommittedWrite(
                    lkg, receipt, verified, "规则变更后校验失败，");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return failAfterCommittedWrite(
                    lkg, receipt, verified, "规则变更返回前只读故障态已变化，");
        }

        List<String> notes = new ArrayList<>();
        notes.add(systemGateNotice(flow.notificationAccess()));
        notes.add(targetCapabilityNotice(target));
        boolean compatibilityUnverified =
                capability.compatibilityUnverified() || flow.compatibilityUnverified();
        if (compatibilityUnverified) {
            notes.add("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return ServiceResult.success(
                new RuleMutationResult(
                        changedRule,
                        verified,
                        lkg,
                        compatibilityUnverified,
                        notes));
    }

    private <T> ServiceResult<T> failAfterWriteAttempt(
            RuleLkg lkg,
            RuleStoreSnapshot initial,
            String failurePrefix) {
        RuleStoreSnapshot observed = readForRecovery();
        if (observed == null) {
            return enterReadOnlyFaultLocked(
                    failurePrefix + "无法验证回滚：写入后状态不可读取，当前规则写入保持只读。");
        }
        if (!observed.readOnlyFaultMessage().isEmpty()) {
            return ServiceResult.failure(observed.readOnlyFaultMessage());
        }
        if (observed.storeVersion() != initial.storeVersion()
                || !matchesRules(initial.rules(), observed.rules())) {
            return enterReadOnlyFaultLocked(
                    failurePrefix
                            + "规则 store 已被外部更新，未覆盖外部规则，当前规则写入保持只读。");
        }
        if (!rollbackRulesOnlyAndVerify(lkg, observed)) {
            return enterReadOnlyFaultLocked(
                    failurePrefix + "无法验证回滚，当前规则写入保持只读。");
        }
        if (!lkg.targetStatesMatch(observed) || !lkg.permissionsMatch(observed)) {
            return enterReadOnlyFaultLocked(
                    failurePrefix
                            + "外部权限或目标状态已变化，已仅回滚规则并保留外部状态，当前规则写入保持只读。");
        }
        return ServiceResult.failure(failurePrefix + "已仅回滚本次规则并验证。");
    }

    private <T> ServiceResult<T> failAfterCommittedReadFailure(
            RuleLkg lkg,
            RuleWriteReceipt receipt,
            String failurePrefix) {
        RuleStoreSnapshot observed = readForRecovery();
        if (observed == null) {
            return enterReadOnlyFaultLocked(
                    failurePrefix + "无法验证回滚：写入后状态不可读取，当前规则写入保持只读。");
        }
        return failAfterCommittedWrite(
                lkg, receipt, observed, failurePrefix);
    }

    private <T> ServiceResult<T> failAfterCommittedWrite(
            RuleLkg lkg,
            RuleWriteReceipt receipt,
            RuleStoreSnapshot observed,
            String failurePrefix) {
        String faultMessage = readOnlyFaultMessageLocked();
        if (!faultMessage.isEmpty()) {
            return ServiceResult.failure(faultMessage);
        }
        if (!observed.serializedJson().equals(receipt.serializedJson())) {
            return enterReadOnlyFaultLocked(
                    failurePrefix
                            + "写入后规则 store 已发生外部变化，未覆盖外部规则，当前规则写入保持只读。");
        }
        boolean externalStateChanged =
                observed.storeVersion() != receipt.storeVersion()
                        || !lkg.targetStatesMatch(observed)
                        || !lkg.permissionsMatch(observed);
        if (!rollbackRulesOnlyAndVerify(lkg, observed)) {
            return enterReadOnlyFaultLocked(
                    failurePrefix + "无法验证回滚，当前规则写入保持只读。");
        }
        if (externalStateChanged) {
            return enterReadOnlyFaultLocked(
                    failurePrefix
                            + "外部权限或目标状态已变化，已仅回滚规则并保留外部状态，当前规则写入保持只读。");
        }
        return ServiceResult.failure(failurePrefix + "已仅回滚本次规则并验证。");
    }

    private RuleStoreSnapshot readForRecovery() {
        ServiceResult<RuleStoreSnapshot> readResult;
        try {
            readResult = gateway.read();
        } catch (RuntimeException exception) {
            return null;
        }
        return readResult.isSuccess() ? readResult.value().orElseThrow() : null;
    }

    private boolean rollbackRulesOnlyAndVerify(
            RuleLkg lkg, RuleStoreSnapshot beforeRollback) {
        RuleLkg preservedState = RuleLkg.capture(beforeRollback);
        ServiceResult<RuleWriteReceipt> rollbackWrite;
        try {
            rollbackWrite =
                    gateway.restoreRules(
                            lkg,
                            beforeRollback.storeVersion(),
                            beforeRollback.rules());
        } catch (RuntimeException exception) {
            return false;
        }
        if (!rollbackWrite.isSuccess()
                || !rollbackWrite
                        .value()
                        .orElseThrow()
                        .serializedJson()
                        .equals(lkg.serializedJson())) {
            return false;
        }
        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return false;
        }
        if (!readBack.isSuccess()) {
            return false;
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        return !hasUnsafeStructure(verified)
                && verified.readOnlyFaultMessage().isEmpty()
                && readOnlyFaultMessageLocked().isEmpty()
                && preservedState.targetStatesMatch(verified)
                && preservedState.permissionsMatch(verified)
                && verified.serializedJson().equals(lkg.serializedJson())
                && matchesRules(lkg.rules(), verified.rules())
                && uniqueRuleIds(verified.rules());
    }

    private <T> ServiceResult<T> enterReadOnlyFaultLocked(String message) {
        try {
            gateway.enterReadOnlyFault(message);
        } catch (RuntimeException exception) {
            return ServiceResult.failure(
                    message + " 无法持久化故障态，请停止写入并检查适配器。");
        }
        return ServiceResult.failure(message);
    }

    private String readOnlyFaultMessageLocked() {
        try {
            String message = gateway.readOnlyFaultMessage();
            return message == null ? "" : message;
        } catch (RuntimeException exception) {
            return "无法读取持久只读故障态，当前规则写入保持只读。";
        }
    }

    private static boolean hasUnsafeStructure(RuleStoreSnapshot snapshot) {
        return !RuleSnapshotValidator.issue(snapshot).isEmpty()
                || !CapabilityProbe.evaluate(snapshot.capabilities()).passed();
    }

    private static boolean sameTargetState(
            TargetDevice selected, TargetDevice current) {
        return selected.deviceId().equals(current.deviceId())
                && selected.displayName().equals(current.displayName())
                && selected.type() == current.type()
                && selected.online() == current.online()
                && selected.reachable() == current.reachable()
                && selected.targetAuthorized() == current.targetAuthorized()
                && selected.receiveCapability() == current.receiveCapability();
    }

    private NotificationRule createRule(
            SourceDevice source,
            TargetDevice target,
            List<NotificationRule> currentRules) {
        NotificationRule existing = findMatchingRule(currentRules, source, target);
        String ruleId =
                existing == null ? nextUniqueRuleId(currentRules) : existing.ruleId();
        String summary =
                "已开启通知转发到"
                        + typeLabel(target)
                        + " "
                        + target.displayName()
                        + "：所有符合条件的通知。";
        String dsl =
                "{\"mode\":\"allEligibleNotifications\",\"ruleText\":"
                        + RuleSerialization.quote(summary)
                        + "}";
        return new NotificationRule(
                ruleId,
                source.deviceId(),
                source.displayName(),
                target.deviceId(),
                target.displayName(),
                "active",
                summary,
                dsl,
                System.currentTimeMillis());
    }

    private NotificationRule createStructuredRule(
            SourceDevice source,
            TargetDevice target,
            List<NotificationRule> currentRules,
            StructuredRuleDraft draft) {
        if (!draft.canSave()) {
            throw new IllegalArgumentException("Structured rule has no filter groups");
        }
        NotificationRule existing =
                findMatchingRule(currentRules, source, target);
        if (existing != null
                && draft.hasRuleId()
                && !draft.ruleId().equals(existing.ruleId())) {
            throw new IllegalArgumentException("Structured draft ruleId does not match target rule");
        }
        String ruleId;
        if (existing != null) {
            ruleId = existing.ruleId();
        } else if (draft.hasRuleId()) {
            if (containsRuleId(currentRules, draft.ruleId())) {
                throw new IllegalArgumentException("Structured draft ruleId is already in use");
            }
            ruleId = draft.ruleId();
        } else {
            ruleId = nextUniqueRuleId(currentRules);
        }
        String status = existing == null ? draft.status() : existing.status();
        long createdAt = existing == null ? draft.createdAt() : existing.createdAt();
        String summary = draft.summary();
        String dsl = StructuredRuleDsl.serialize(draft);
        return new NotificationRule(
                ruleId,
                source.deviceId(),
                source.displayName(),
                target.deviceId(),
                target.displayName(),
                status,
                summary,
                dsl,
                createdAt);
    }

    private String nextUniqueRuleId(List<NotificationRule> rules) {
        Set<String> existing = new HashSet<>();
        for (NotificationRule rule : rules) {
            existing.add(rule.ruleId());
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = ruleIdSupplier.get();
            if (candidate != null
                    && !candidate.trim().isEmpty()
                    && !existing.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique ruleId");
    }

    private static List<NotificationRule> upsert(
            List<NotificationRule> rules, NotificationRule replacement) {
        List<NotificationRule> candidate = new ArrayList<>(rules.size() + 1);
        boolean inserted = false;
        for (NotificationRule existing : rules) {
            boolean sameKey =
                    existing.sourceDeviceId().equals(replacement.sourceDeviceId())
                            && existing.targetDeviceId().equals(replacement.targetDeviceId());
            if (sameKey) {
                if (!inserted) {
                    candidate.add(replacement);
                    inserted = true;
                }
            } else {
                candidate.add(existing);
            }
        }
        if (!inserted) {
            candidate.add(replacement);
        }
        return candidate;
    }

    private static NotificationRule findMatchingRule(
            List<NotificationRule> rules, SourceDevice source, TargetDevice target) {
        for (NotificationRule rule : rules) {
            if (rule.sourceDeviceId().equals(source.deviceId())
                    && rule.targetDeviceId().equals(target.deviceId())) {
                return rule;
            }
        }
        return null;
    }

    private static int findRuleIndex(List<NotificationRule> rules, String ruleId) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).ruleId().equals(ruleId)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isLifecycleStatus(String status) {
        return "active".equals(status) || "paused".equals(status);
    }

    private static String systemGateNotice(NotificationAccessState access) {
        if (access == NotificationAccessState.GRANTED) {
            return "系统闸门：系统通知使用权已授予；发送链路仍可能阻止或丢弃通知。";
        }
        return "系统闸门：系统通知使用权"
                + (access == NotificationAccessState.MISSING ? "缺失" : "已撤销")
                + "，规则虽可创建，但通知转发受阻。";
    }

    private static String targetCapabilityNotice(TargetDevice target) {
        StringBuilder note = new StringBuilder("目标能力限制：");
        note.append(target.displayName()).append(" 支持通知接收");
        if (!target.targetAuthorized()) {
            note.append("，但目标尚未授权");
        }
        if (!target.online()) {
            note.append("，且当前离线");
        } else if (!target.reachable()) {
            note.append("，且当前不可达");
        }
        return note.append('。').toString();
    }

    private static String typeLabel(TargetDevice target) {
        switch (target.type()) {
            case PC:
                return "电脑";
            case PAD:
                return "平板";
            case MAC:
                return "Mac";
            default:
                return target.type().name();
        }
    }

    private static TargetDevice findTarget(NotificationFlowSnapshot flow, String deviceId) {
        for (TargetDevice target : flow.targets()) {
            if (target.deviceId().equals(deviceId)) {
                return target;
            }
        }
        return null;
    }

    private static int matchingTargetCount(
            List<NotificationRule> rules, NotificationRule target) {
        int count = 0;
        for (NotificationRule rule : rules) {
            if (rule.sourceDeviceId().equals(target.sourceDeviceId())
                    && rule.targetDeviceId().equals(target.targetDeviceId())) {
                count++;
            }
        }
        return count;
    }

    private static boolean targetRuleMatches(
            List<NotificationRule> rules, NotificationRule target) {
        for (NotificationRule rule : rules) {
            if (sameRule(rule, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean uniqueRuleIds(List<NotificationRule> rules) {
        Set<String> ids = new HashSet<>();
        for (NotificationRule rule : rules) {
            if (!ids.add(rule.ruleId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsRuleId(
            List<NotificationRule> rules, String ruleId) {
        for (NotificationRule rule : rules) {
            if (rule.ruleId().equals(ruleId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRules(
            List<NotificationRule> expected, List<NotificationRule> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!sameRule(expected.get(index), actual.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRule(NotificationRule left, NotificationRule right) {
        return left.ruleId().equals(right.ruleId())
                && left.sourceDeviceId().equals(right.sourceDeviceId())
                && left.sourceDeviceName().equals(right.sourceDeviceName())
                && left.targetDeviceId().equals(right.targetDeviceId())
                && left.targetDeviceName().equals(right.targetDeviceName())
                && left.status().equals(right.status())
                && left.summary().equals(right.summary())
                && left.dslJson().equals(right.dslJson())
                && left.createdAt() == right.createdAt();
    }

    @FunctionalInterface
    private interface RuleFactory {
        NotificationRule create(
                SourceDevice source,
                TargetDevice target,
                List<NotificationRule> currentRules);
    }
}
