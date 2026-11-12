package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.ArrayList;
import java.util.Collections;
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
    private volatile String localReadOnlyFaultMessage = "";
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
            String expectedTargetDeviceId,
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
                    expectedTargetDeviceId,
                    expectedStatus,
                    nextStatus,
                    expectedTargetAuthorized,
                    false);
        }
    }

    public ServiceResult<RuleMutationResult> deleteRule(
            String ruleId,
            String expectedTargetDeviceId,
            boolean expectedTargetAuthorized) {
        synchronized (PROCESS_WRITE_LOCK) {
            return mutateRuleLocked(
                    ruleId,
                    expectedTargetDeviceId,
                    "",
                    "",
                    expectedTargetAuthorized,
                    true);
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
        ServiceResult<PreparedRuleWrite> prepared =
                prepareRuleWriteLocked(selection, ruleFactory);
        if (!prepared.isSuccess()) {
            return ServiceResult.failure(prepared.userMessage());
        }
        return commitPreparedWrite(prepared.value().orElseThrow());
    }

    private ServiceResult<PreparedRuleWrite> prepareRuleWriteLocked(
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

        boolean compatibilityUnverified =
                capability.compatibilityUnverified() || flow.compatibilityUnverified();
        return ServiceResult.success(
                new PreparedRuleWrite(
                        current,
                        lkg,
                        createdRule,
                        candidate,
                        expectedSerialized,
                        target,
                        compatibilityUnverified,
                        selection.offlineWarning(),
                        "规则写入"));
    }

    private ServiceResult<RuleCreationResult> commitPreparedWrite(
            PreparedRuleWrite plan) {
        return commitPreparedWriteFrom(plan, plan.initial);
    }

    private ServiceResult<RuleCreationResult> commitPreparedWriteFrom(
            PreparedRuleWrite plan, RuleStoreSnapshot writeBaseline) {
        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult =
                    gateway.write(
                            plan.candidate,
                            writeBaseline.storeVersion(),
                            writeBaseline.rules());
        } catch (RuntimeException exception) {
            return failAfterWriteAttempt(
                    plan.lkg, writeBaseline, plan.failurePrefix + "异常，");
        }
        if (!writeResult.isSuccess()) {
            return failAfterWriteAttempt(
                    plan.lkg, writeBaseline, plan.failurePrefix + "未完成，");
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return failAfterCommittedReadFailure(
                    plan.lkg, receipt, "写入后回读异常，");
        }
        if (!readBack.isSuccess()) {
            return failAfterCommittedReadFailure(
                    plan.lkg, receipt, "写入后无法回读规则，");
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        if (!verified.readOnlyFaultMessage().isEmpty()
                || verified.storeVersion() != receipt.storeVersion()
                || hasUnsafeStructure(verified)
                || !plan.lkg.targetStatesMatch(verified)
                || !plan.lkg.permissionsMatch(verified)
                || !receipt.serializedJson().equals(plan.expectedSerialized)
                || !verified.serializedJson().equals(plan.expectedSerialized)
                || !matchesRules(plan.candidate, verified.rules())
                || !uniqueRuleIds(plan.candidate)
                || matchingTargetCount(plan.candidate, plan.createdRule) != 1
                || !targetRuleMatches(plan.candidate, plan.createdRule)) {
            return failAfterCommittedWrite(
                    plan.lkg, receipt, verified, "写入后校验失败，");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return failAfterCommittedWrite(
                    plan.lkg, receipt, verified, "写入返回前只读故障态已变化，");
        }

        return ServiceResult.success(creationResult(plan, verified));
    }

    private RuleCreationResult creationResult(
            PreparedRuleWrite plan, RuleStoreSnapshot current) {
        List<String> notes = new ArrayList<>();
        notes.add(systemGateNotice(current.flowSnapshot().notificationAccess()));
        notes.add(targetCapabilityNotice(plan.target));
        if (!plan.offlineWarning.isEmpty()) {
            notes.add(plan.offlineWarning);
        }
        boolean compatibilityUnverified = compatibilityUnverified(current);
        if (compatibilityUnverified) {
            notes.add("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return new RuleCreationResult(
                plan.createdRule, plan.lkg, compatibilityUnverified, notes);
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
            String faultMessage = Objects.requireNonNull(message, "message");
            localReadOnlyFaultMessage = faultMessage;
            gateway.enterReadOnlyFault(faultMessage);
        }
    }

    RecoverableWriteAttempt attemptStructuredRuleRecovery(
            ConfirmedTargetSelection selection, StructuredRuleDraft draft) {
        synchronized (PROCESS_WRITE_LOCK) {
            if (draft == null) {
                return RecoverableWriteAttempt.failed(
                        "未提供结构化规则，未执行任何写入。");
            }
            ServiceResult<PreparedRuleWrite> prepared =
                    prepareRuleWriteLocked(
                            selection,
                            (source, target, rules) ->
                                    createStructuredRule(source, target, rules, draft));
            if (!prepared.isSuccess()) {
                return RecoverableWriteAttempt.failed(prepared.userMessage());
            }
            return attemptPreparedWriteRecovery(prepared.value().orElseThrow());
        }
    }

    RecoverableWriteAttempt attemptAllNotificationsRuleRecovery(
            ConfirmedTargetSelection selection) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<PreparedRuleWrite> prepared =
                    prepareRuleWriteLocked(
                            selection,
                            (source, target, rules) ->
                                    createRule(source, target, rules));
            if (!prepared.isSuccess()) {
                return RecoverableWriteAttempt.failed(prepared.userMessage());
            }
            return attemptPreparedWriteRecovery(prepared.value().orElseThrow());
        }
    }

    private RecoverableWriteAttempt attemptPreparedWriteRecovery(
            PreparedRuleWrite plan) {
        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult =
                    gateway.write(
                            plan.candidate,
                            plan.initial.storeVersion(),
                            plan.initial.rules());
        } catch (RuntimeException exception) {
            return RecoverableWriteAttempt.failed(
                    failAfterWriteAttempt(
                                    plan.lkg,
                                    plan.initial,
                                    plan.failurePrefix + "异常，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        if (!writeResult.isSuccess()) {
            return RecoverableWriteAttempt.failed(
                    failAfterWriteAttempt(
                                    plan.lkg,
                                    plan.initial,
                                    plan.failurePrefix + "未完成，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return RecoverableWriteAttempt.failed(
                    failAfterCommittedReadFailure(
                                    plan.lkg, receipt, "写入后回读异常，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        if (!readBack.isSuccess()) {
            return RecoverableWriteAttempt.failed(
                    failAfterCommittedReadFailure(
                                    plan.lkg, receipt, "写入后无法回读规则，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        RuleStoreSnapshot observed = readBack.value().orElseThrow();
        boolean receiptWasVerified =
                observed.serializedJson().equals(receipt.serializedJson());
        boolean currentStateChanged =
                !receiptWasVerified
                        || observed.storeVersion() != receipt.storeVersion()
                        || !plan.lkg.targetStatesMatch(observed)
                        || !plan.lkg.permissionsMatch(observed);
        if (!observed.readOnlyFaultMessage().isEmpty() || currentStateChanged) {
            ServiceResult<RuleCreationResult> failure =
                    failAfterCommittedWrite(
                            plan.lkg, receipt, observed, "写入后校验失败，");
            return RecoverableWriteAttempt.unsafe(
                    failure.userMessage(), compatibilityUnverified(observed));
        }
        boolean structuralFailure =
                hasUnsafeStructure(observed)
                        || !receipt.serializedJson().equals(plan.expectedSerialized)
                        || !observed.serializedJson().equals(plan.expectedSerialized)
                        || !matchesRules(plan.candidate, observed.rules())
                        || !uniqueRuleIds(plan.candidate)
                        || matchingTargetCount(plan.candidate, plan.createdRule) != 1
                        || !targetRuleMatches(plan.candidate, plan.createdRule);
        if (structuralFailure) {
            return RecoverableWriteAttempt.recoverable(
                    plan,
                    observed,
                    "写入后校验失败；已保留第一次写入前 LKG。"
                            + "推荐立即回滚；也可重新读取并验证当前状态后进行一次受控重试。");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return RecoverableWriteAttempt.failed(
                    failAfterCommittedWrite(
                                    plan.lkg,
                                    receipt,
                                    observed,
                                    "写入返回前只读故障态已变化，")
                            .userMessage(),
                    compatibilityUnverified(observed));
        }
        return RecoverableWriteAttempt.success(creationResult(plan, observed));
    }

    RecoveryRetryAttempt retryPreparedWriteRecovery(
            PreparedRuleWrite plan,
            RuleStoreSnapshot failedSnapshot,
            boolean skipPreRetryValidation) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<RuleStoreSnapshot> readResult;
            try {
                readResult = gateway.read();
            } catch (RuntimeException exception) {
                return RecoveryRetryAttempt.notAttempted(
                        "当前状态无法读取，受控重试已禁止。");
            }
            if (!readResult.isSuccess()) {
                return RecoveryRetryAttempt.notAttempted(
                        "当前状态无法读取，受控重试已禁止。");
            }
            RuleStoreSnapshot current = readResult.value().orElseThrow();
            String mandatoryIssue = mandatoryRecoveryIssue(plan, current);
            if (!mandatoryIssue.isEmpty()) {
                return RecoveryRetryAttempt.notAttempted(mandatoryIssue);
            }
            if (!skipPreRetryValidation
                    && !sameRecoveryState(failedSnapshot, current)) {
                return RecoveryRetryAttempt.notAttempted(
                        "当前状态不安全：写入失败后的 store 已被外部更新，受控重试已禁止。");
            }

            ServiceResult<RuleCreationResult> result =
                    commitPreparedWriteFrom(plan, current);
            if (result.isSuccess()) {
                return RecoveryRetryAttempt.succeeded(result.value().orElseThrow());
            }
            return RecoveryRetryAttempt.failed(
                    result.userMessage(), !readOnlyFaultMessageLocked().isEmpty());
        }
    }

    RecoveryInspection inspectPreparedWriteRecovery(
            PreparedRuleWrite plan,
            RuleStoreSnapshot failedSnapshot,
            boolean skipPreRetryValidation) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<RuleStoreSnapshot> readResult;
            try {
                readResult = gateway.read();
            } catch (RuntimeException exception) {
                return new RecoveryInspection(false, false, "当前状态无法读取，受控重试已禁止。");
            }
            if (!readResult.isSuccess()) {
                return new RecoveryInspection(false, false, "当前状态无法读取，受控重试已禁止。");
            }
            RuleStoreSnapshot current = readResult.value().orElseThrow();
            if (!skipPreRetryValidation
                    && !sameRecoveryState(failedSnapshot, current)) {
                return new RecoveryInspection(
                        false,
                        false,
                        "当前状态不安全：写入失败后的 store 已被外部更新，受控重试已禁止。");
            }
            String mandatoryIssue = mandatoryRecoveryIssue(plan, current);
            if (!mandatoryIssue.isEmpty()) {
                return new RecoveryInspection(false, true, mandatoryIssue);
            }
            return new RecoveryInspection(true, true, "");
        }
    }

    RecoveryRollbackAttempt rollbackPreparedWriteRecovery(
            PreparedRuleWrite plan, RuleStoreSnapshot failedSnapshot) {
        return rollbackPreparedRecovery(plan.lkg, failedSnapshot);
    }

    private RecoveryRollbackAttempt rollbackPreparedRecovery(
            RuleLkg lkg, RuleStoreSnapshot failedSnapshot) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<RuleStoreSnapshot> readResult;
            try {
                readResult = gateway.read();
            } catch (RuntimeException exception) {
                return RecoveryRollbackAttempt.failed(
                        enterRecoveryFault(
                                "无法验证回滚：当前规则状态不可读取，请重新检测或再次尝试回滚。"));
            }
            if (!readResult.isSuccess()) {
                return RecoveryRollbackAttempt.failed(
                        enterRecoveryFault(
                                "无法验证回滚：当前规则状态不可读取，请重新检测或再次尝试回滚。"));
            }
            RuleStoreSnapshot current = readResult.value().orElseThrow();
            boolean persistentFault =
                    !current.readOnlyFaultMessage().isEmpty()
                            || !localReadOnlyFaultMessage.isEmpty();
            if (!matchesRules(current.rules(), failedSnapshot.rules())
                    || (!persistentFault
                            && current.storeVersion() != failedSnapshot.storeVersion())) {
                return RecoveryRollbackAttempt.failed(
                        enterRecoveryFault(
                                "回滚前检测到规则 store 已发生外部变化，"
                                        + "未覆盖外部规则，请重新检测或再次尝试回滚。"));
            }
            boolean verified =
                    persistentFault
                            ? rollbackRulesOnlyAndVerifyRecovery(lkg, current)
                            : rollbackRulesOnlyAndVerify(lkg, current);
            if (!verified) {
                return RecoveryRollbackAttempt.failed(
                        enterRecoveryFault(
                                "无法验证回滚：原状态恢复校验失败，"
                                        + "请重新检测或再次尝试回滚。"));
            }
            return RecoveryRollbackAttempt.success();
        }
    }

    private boolean rollbackRulesOnlyAndVerifyRecovery(
            RuleLkg lkg, RuleStoreSnapshot beforeRollback) {
        RuleLkg preservedState = RuleLkg.capture(beforeRollback);
        ServiceResult<RuleWriteReceipt> rollbackWrite;
        try {
            rollbackWrite =
                    gateway.restoreRulesForVerifiedRecovery(
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
        boolean recovered =
                !hasUnsafeStructure(verified)
                && verified.readOnlyFaultMessage().isEmpty()
                && preservedState.targetStatesMatch(verified)
                && preservedState.permissionsMatch(verified)
                && verified.serializedJson().equals(lkg.serializedJson())
                && matchesRules(lkg.rules(), verified.rules())
                && uniqueRuleIds(verified.rules());
        if (recovered) {
            localReadOnlyFaultMessage = "";
        }
        return recovered;
    }

    private String mandatoryRecoveryIssue(
            PreparedRuleWrite plan, RuleStoreSnapshot current) {
        return mandatoryRecoveryIssue(plan.initial, plan.target, current);
    }

    private String mandatoryRecoveryIssue(
            RuleStoreSnapshot initial,
            TargetDevice expectedTarget,
            RuleStoreSnapshot current) {
        String persistentFault = readOnlyFaultMessageLocked();
        if (!persistentFault.isEmpty()) {
            return "当前处于持久只读故障态，受控重试已禁止：" + persistentFault;
        }
        NotificationFlowSnapshot flow = current.flowSnapshot();
        if (flow.staleCache()
                || (flow.connectionState() != ConnectionState.CONNECTED
                        && flow.connectionState()
                                != ConnectionState.COMPATIBILITY_UNVERIFIED)) {
            return "当前规则快照不可用于重试。";
        }
        CapabilityReport capability = CapabilityProbe.evaluate(current.capabilities());
        if (!capability.passed()) {
            return capability.readOnlyMessage() + " 调试模式不会绕过能力探测。";
        }
        String snapshotIssue = RuleSnapshotValidator.issue(current);
        if (!snapshotIssue.isEmpty()) {
            return snapshotIssue + " 调试模式不会绕过未知字段或 DSL 禁写。";
        }
        if (!initial.sourceDevice().deviceId().equals(current.sourceDevice().deviceId())) {
            return "来源设备已变化，受控重试已禁止。";
        }
        TargetDevice currentTarget = findTarget(flow, expectedTarget.deviceId());
        if (currentTarget == null
                || !currentTarget.supportsNotificationReceive()
                || !sameTargetState(expectedTarget, currentTarget)) {
            return "目标能力或授权已变化，受控重试已禁止。";
        }
        return "";
    }

    private static boolean sameRecoveryState(
            RuleStoreSnapshot failed, RuleStoreSnapshot current) {
        if (failed.storeVersion() != current.storeVersion()
                || !matchesRules(failed.rules(), current.rules())) {
            return false;
        }
        RuleLkg failedState = RuleLkg.capture(failed);
        return failedState.targetStatesMatch(current)
                && failedState.permissionsMatch(current);
    }

    private String enterRecoveryFault(String message) {
        localReadOnlyFaultMessage = message;
        try {
            gateway.enterReadOnlyFault(message);
            return message;
        } catch (RuntimeException exception) {
            return message + " 无法持久化故障态，请停止写入并检查适配器。";
        }
    }

    RecoverableMutationAttempt attemptChangeRuleStatusRecovery(
            String ruleId,
            String expectedTargetDeviceId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized) {
        if (!isLifecycleStatus(expectedStatus)
                || !isLifecycleStatus(nextStatus)
                || expectedStatus.equals(nextStatus)) {
            return RecoverableMutationAttempt.failed(
                    "仅支持 active 与 paused 之间的规则状态变更。");
        }
        return attemptMutationRecovery(
                () ->
                        prepareRuleMutationLocked(
                                ruleId,
                                expectedTargetDeviceId,
                                expectedStatus,
                                nextStatus,
                                expectedTargetAuthorized,
                                false));
    }

    RecoverableMutationAttempt attemptDeleteRuleRecovery(
            String ruleId,
            String expectedTargetDeviceId,
            boolean expectedTargetAuthorized) {
        return attemptMutationRecovery(
                () ->
                        prepareRuleMutationLocked(
                                ruleId,
                                expectedTargetDeviceId,
                                "",
                                "",
                                expectedTargetAuthorized,
                                true));
    }

    private RecoverableMutationAttempt attemptMutationRecovery(
            java.util.function.Supplier<ServiceResult<PreparedRuleMutation>> preparation) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<PreparedRuleMutation> prepared = preparation.get();
            if (!prepared.isSuccess()) {
                return RecoverableMutationAttempt.failed(prepared.userMessage());
            }
            return attemptPreparedMutationRecovery(prepared.value().orElseThrow());
        }
    }

    private RecoverableMutationAttempt attemptPreparedMutationRecovery(
            PreparedRuleMutation plan) {
        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult =
                    gateway.write(
                            plan.candidate,
                            plan.initial.storeVersion(),
                            plan.initial.rules());
        } catch (RuntimeException exception) {
            return RecoverableMutationAttempt.failed(
                    failAfterWriteAttempt(
                                    plan.lkg, plan.initial, "规则变更写入异常，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        if (!writeResult.isSuccess()) {
            return RecoverableMutationAttempt.failed(
                    failAfterWriteAttempt(
                                    plan.lkg, plan.initial, "规则变更写入未完成，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return RecoverableMutationAttempt.failed(
                    failAfterCommittedReadFailure(
                                    plan.lkg, receipt, "规则变更后回读异常，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        if (!readBack.isSuccess()) {
            return RecoverableMutationAttempt.failed(
                    failAfterCommittedReadFailure(
                                    plan.lkg, receipt, "规则变更后无法回读规则，")
                            .userMessage(),
                    plan.compatibilityUnverified());
        }
        RuleStoreSnapshot observed = readBack.value().orElseThrow();
        boolean receiptWasVerified =
                observed.serializedJson().equals(receipt.serializedJson());
        boolean currentStateChanged =
                !receiptWasVerified
                        || observed.storeVersion() != receipt.storeVersion()
                        || !plan.lkg.targetStatesMatch(observed)
                        || !plan.lkg.permissionsMatch(observed);
        if (!observed.readOnlyFaultMessage().isEmpty() || currentStateChanged) {
            ServiceResult<RuleMutationResult> failure =
                    failAfterCommittedWrite(
                            plan.lkg, receipt, observed, "规则变更后校验失败，");
            return RecoverableMutationAttempt.unsafe(
                    failure.userMessage(), compatibilityUnverified(observed));
        }
        boolean structuralFailure =
                hasUnsafeStructure(observed)
                        || !receipt.serializedJson().equals(plan.expectedSerialized)
                        || !observed.serializedJson().equals(plan.expectedSerialized)
                        || !matchesRules(plan.candidate, observed.rules())
                        || !uniqueRuleIds(plan.candidate);
        if (structuralFailure) {
            return RecoverableMutationAttempt.recoverable(
                    plan,
                    observed,
                    "写入后校验失败；已保留第一次写入前 LKG。"
                            + "推荐立即回滚；也可重新读取并验证当前状态后进行一次受控重试。");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return RecoverableMutationAttempt.failed(
                    failAfterCommittedWrite(
                                    plan.lkg,
                                    receipt,
                                    observed,
                                    "规则变更返回前只读故障态已变化，")
                            .userMessage(),
                    compatibilityUnverified(observed));
        }
        return RecoverableMutationAttempt.success(
                mutationResult(plan, observed, observed));
    }

    RecoveryMutationRetryAttempt retryPreparedMutationRecovery(
            PreparedRuleMutation plan,
            RuleStoreSnapshot failedSnapshot,
            boolean skipPreRetryValidation) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<RuleStoreSnapshot> readResult;
            try {
                readResult = gateway.read();
            } catch (RuntimeException exception) {
                return RecoveryMutationRetryAttempt.notAttempted(
                        "当前状态无法读取，受控重试已禁止。");
            }
            if (!readResult.isSuccess()) {
                return RecoveryMutationRetryAttempt.notAttempted(
                        "当前状态无法读取，受控重试已禁止。");
            }
            RuleStoreSnapshot current = readResult.value().orElseThrow();
            String mandatoryIssue = mandatoryMutationRecoveryIssue(plan, current);
            if (!mandatoryIssue.isEmpty()) {
                return RecoveryMutationRetryAttempt.notAttempted(mandatoryIssue);
            }
            if (!skipPreRetryValidation
                    && !sameRecoveryState(failedSnapshot, current)) {
                return RecoveryMutationRetryAttempt.notAttempted(
                        "当前状态不安全：写入失败后的 store 已被外部更新，受控重试已禁止。");
            }

            ServiceResult<RuleMutationResult> result =
                    commitPreparedMutation(plan, current);
            if (result.isSuccess()) {
                return RecoveryMutationRetryAttempt.succeeded(result.value().orElseThrow());
            }
            return RecoveryMutationRetryAttempt.failed(
                    result.userMessage(), !readOnlyFaultMessageLocked().isEmpty());
        }
    }

    RecoveryInspection inspectPreparedMutationRecovery(
            PreparedRuleMutation plan,
            RuleStoreSnapshot failedSnapshot,
            boolean skipPreRetryValidation) {
        synchronized (PROCESS_WRITE_LOCK) {
            ServiceResult<RuleStoreSnapshot> readResult;
            try {
                readResult = gateway.read();
            } catch (RuntimeException exception) {
                return new RecoveryInspection(false, false, "当前状态无法读取，受控重试已禁止。");
            }
            if (!readResult.isSuccess()) {
                return new RecoveryInspection(false, false, "当前状态无法读取，受控重试已禁止。");
            }
            RuleStoreSnapshot current = readResult.value().orElseThrow();
            if (!skipPreRetryValidation
                    && !sameRecoveryState(failedSnapshot, current)) {
                return new RecoveryInspection(
                        false,
                        false,
                        "当前状态不安全：写入失败后的 store 已被外部更新，受控重试已禁止。");
            }
            String mandatoryIssue = mandatoryMutationRecoveryIssue(plan, current);
            if (!mandatoryIssue.isEmpty()) {
                return new RecoveryInspection(false, true, mandatoryIssue);
            }
            return new RecoveryInspection(true, true, "");
        }
    }

    RecoveryRollbackAttempt rollbackPreparedMutationRecovery(
            PreparedRuleMutation plan, RuleStoreSnapshot failedSnapshot) {
        return rollbackPreparedRecovery(plan.lkg, failedSnapshot);
    }

    private String mandatoryMutationRecoveryIssue(
            PreparedRuleMutation plan, RuleStoreSnapshot current) {
        String mandatoryIssue = mandatoryRecoveryIssue(plan.initial, plan.target, current);
        if (!mandatoryIssue.isEmpty()) {
            return mandatoryIssue;
        }
        if (current.flowSnapshot().notificationAccess() != NotificationAccessState.GRANTED) {
            return "系统通知使用权未满足，受控重试已禁止。";
        }
        return "";
    }

    private ServiceResult<RuleMutationResult> mutateRuleLocked(
            String ruleId,
            String expectedTargetDeviceId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized,
            boolean delete) {
        ServiceResult<PreparedRuleMutation> prepared =
                prepareRuleMutationLocked(
                        ruleId,
                        expectedTargetDeviceId,
                        expectedStatus,
                        nextStatus,
                        expectedTargetAuthorized,
                        delete);
        if (!prepared.isSuccess()) {
            return ServiceResult.failure(prepared.userMessage());
        }
        PreparedRuleMutation plan = prepared.value().orElseThrow();
        return commitPreparedMutation(plan, plan.initial);
    }

    private ServiceResult<PreparedRuleMutation> prepareRuleMutationLocked(
            String ruleId,
            String expectedTargetDeviceId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized,
            boolean delete) {
        if (ruleId == null || ruleId.trim().isEmpty()) {
            return ServiceResult.failure("未指定要修改的规则，未执行任何写入。");
        }
        if (expectedTargetDeviceId == null
                || expectedTargetDeviceId.trim().isEmpty()) {
            return ServiceResult.failure("未指定预期目标，未执行任何写入。");
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
        if (!expectedTargetDeviceId.equals(currentRule.targetDeviceId())) {
            return ServiceResult.failure(
                    "规则目标已在外部变化，未执行任何写入；不会删除或撤销错误目标。");
        }
        TargetDevice target = findTarget(flow, currentRule.targetDeviceId());
        if (target == null) {
            return ServiceResult.failure("规则目标不在当前快照中，未执行任何写入。");
        }
        if (flow.notificationAccess() != NotificationAccessState.GRANTED) {
            return ServiceResult.failure(
                    "系统通知使用权未满足，未执行任何生命周期写入。");
        }
        if (target.targetAuthorized() != expectedTargetAuthorized) {
            return ServiceResult.failure(
                    "目标授权已在外部变化，未执行任何写入；不会自动重新授权或覆盖外部变化。");
        }
        if (!target.targetAuthorized()) {
            return ServiceResult.failure(
                    "目标尚未授权，未执行任何生命周期写入。");
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

        return ServiceResult.success(
                new PreparedRuleMutation(
                        current,
                        lkg,
                        changedRule,
                        currentRule.targetDeviceId(),
                        candidate,
                        expectedSerialized,
                        target,
                        compatibilityUnverified(current)));
    }

    private ServiceResult<RuleMutationResult> commitPreparedMutation(
            PreparedRuleMutation plan, RuleStoreSnapshot writeBaseline) {
        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult =
                    gateway.write(
                            plan.candidate,
                            writeBaseline.storeVersion(),
                            writeBaseline.rules());
        } catch (RuntimeException exception) {
            return failAfterWriteAttempt(
                    plan.lkg, writeBaseline, "规则变更写入异常，");
        }
        if (!writeResult.isSuccess()) {
            return failAfterWriteAttempt(
                    plan.lkg, writeBaseline, "规则变更写入未完成，");
        }
        RuleWriteReceipt receipt = writeResult.value().orElseThrow();

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return failAfterCommittedReadFailure(
                    plan.lkg, receipt, "规则变更后回读异常，");
        }
        if (!readBack.isSuccess()) {
            return failAfterCommittedReadFailure(
                    plan.lkg, receipt, "规则变更后无法回读规则，");
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        if (!verified.readOnlyFaultMessage().isEmpty()
                || verified.storeVersion() != receipt.storeVersion()
                || hasUnsafeStructure(verified)
                || !plan.lkg.targetStatesMatch(verified)
                || !plan.lkg.permissionsMatch(verified)
                || !receipt.serializedJson().equals(plan.expectedSerialized)
                || !verified.serializedJson().equals(plan.expectedSerialized)
                || !matchesRules(plan.candidate, verified.rules())
                || !uniqueRuleIds(plan.candidate)) {
            return failAfterCommittedWrite(
                    plan.lkg, receipt, verified, "规则变更后校验失败，");
        }
        if (!readOnlyFaultMessageLocked().isEmpty()) {
            return failAfterCommittedWrite(
                    plan.lkg, receipt, verified, "规则变更返回前只读故障态已变化，");
        }

        return ServiceResult.success(mutationResult(plan, writeBaseline, verified));
    }

    private RuleMutationResult mutationResult(
            PreparedRuleMutation plan,
            RuleStoreSnapshot current,
            RuleStoreSnapshot verified) {
        List<String> notes = new ArrayList<>();
        NotificationFlowSnapshot flow = current.flowSnapshot();
        notes.add(systemGateNotice(flow.notificationAccess()));
        notes.add(targetCapabilityNotice(plan.target));
        boolean compatibilityUnverified = compatibilityUnverified(current);
        if (compatibilityUnverified) {
            notes.add("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return new RuleMutationResult(
                plan.changedRule,
                plan.affectedTargetDeviceId,
                verified,
                plan.lkg,
                compatibilityUnverified,
                notes);
    }

    private static boolean compatibilityUnverified(RuleStoreSnapshot snapshot) {
        return CapabilityProbe.evaluate(snapshot.capabilities()).compatibilityUnverified()
                || snapshot.flowSnapshot().compatibilityUnverified();
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
        localReadOnlyFaultMessage = message;
        try {
            gateway.enterReadOnlyFault(message);
        } catch (RuntimeException exception) {
            return ServiceResult.failure(
                    message + " 无法持久化故障态，请停止写入并检查适配器。");
        }
        return ServiceResult.failure(message);
    }

    private String readOnlyFaultMessageLocked() {
        if (!localReadOnlyFaultMessage.isEmpty()) {
            return localReadOnlyFaultMessage;
        }
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

    static final class PreparedRuleWrite {
        private final RuleStoreSnapshot initial;
        private final RuleLkg lkg;
        private final NotificationRule createdRule;
        private final List<NotificationRule> candidate;
        private final String expectedSerialized;
        private final TargetDevice target;
        private final boolean compatibilityUnverified;
        private final String offlineWarning;
        private final String failurePrefix;

        private PreparedRuleWrite(
                RuleStoreSnapshot initial,
                RuleLkg lkg,
                NotificationRule createdRule,
                List<NotificationRule> candidate,
                String expectedSerialized,
                TargetDevice target,
                boolean compatibilityUnverified,
                String offlineWarning,
                String failurePrefix) {
            this.initial = Objects.requireNonNull(initial, "initial");
            this.lkg = Objects.requireNonNull(lkg, "lkg");
            this.createdRule = Objects.requireNonNull(createdRule, "createdRule");
            this.candidate =
                    Collections.unmodifiableList(
                            new ArrayList<>(Objects.requireNonNull(candidate, "candidate")));
            this.expectedSerialized =
                    Objects.requireNonNull(expectedSerialized, "expectedSerialized");
            this.target = Objects.requireNonNull(target, "target");
            this.compatibilityUnverified = compatibilityUnverified;
            this.offlineWarning = Objects.requireNonNull(offlineWarning, "offlineWarning");
            this.failurePrefix = Objects.requireNonNull(failurePrefix, "failurePrefix");
        }

        boolean compatibilityUnverified() {
            return compatibilityUnverified;
        }
    }

    static final class PreparedRuleMutation {
        private final RuleStoreSnapshot initial;
        private final RuleLkg lkg;
        private final NotificationRule changedRule;
        private final String affectedTargetDeviceId;
        private final List<NotificationRule> candidate;
        private final String expectedSerialized;
        private final TargetDevice target;
        private final boolean compatibilityUnverified;

        private PreparedRuleMutation(
                RuleStoreSnapshot initial,
                RuleLkg lkg,
                NotificationRule changedRule,
                String affectedTargetDeviceId,
                List<NotificationRule> candidate,
                String expectedSerialized,
                TargetDevice target,
                boolean compatibilityUnverified) {
            this.initial = Objects.requireNonNull(initial, "initial");
            this.lkg = Objects.requireNonNull(lkg, "lkg");
            this.changedRule = changedRule;
            this.affectedTargetDeviceId =
                    Objects.requireNonNull(affectedTargetDeviceId, "affectedTargetDeviceId");
            this.candidate =
                    Collections.unmodifiableList(
                            new ArrayList<>(Objects.requireNonNull(candidate, "candidate")));
            this.expectedSerialized =
                    Objects.requireNonNull(expectedSerialized, "expectedSerialized");
            this.target = Objects.requireNonNull(target, "target");
            this.compatibilityUnverified = compatibilityUnverified;
        }

        boolean compatibilityUnverified() {
            return compatibilityUnverified;
        }
    }

    static final class RecoverableMutationAttempt {
        private final ServiceResult<RuleMutationResult> result;
        private final PreparedRuleMutation plan;
        private final RuleStoreSnapshot failedSnapshot;
        private final String failureReason;
        private final boolean unsafe;
        private final boolean compatibilityUnverified;

        private RecoverableMutationAttempt(
                ServiceResult<RuleMutationResult> result,
                PreparedRuleMutation plan,
                RuleStoreSnapshot failedSnapshot,
                String failureReason,
                boolean unsafe,
                boolean compatibilityUnverified) {
            this.result = Objects.requireNonNull(result, "result");
            this.plan = plan;
            this.failedSnapshot = failedSnapshot;
            this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
            this.unsafe = unsafe;
            this.compatibilityUnverified = compatibilityUnverified;
        }

        private static RecoverableMutationAttempt success(RuleMutationResult result) {
            return new RecoverableMutationAttempt(
                    ServiceResult.success(result),
                    null,
                    null,
                    "",
                    false,
                    result.compatibilityUnverified());
        }

        private static RecoverableMutationAttempt failed(String message) {
            return new RecoverableMutationAttempt(
                    ServiceResult.failure(message), null, null, message, false, false);
        }

        private static RecoverableMutationAttempt failed(
                String message, boolean compatibilityUnverified) {
            return new RecoverableMutationAttempt(
                    ServiceResult.failure(message),
                    null,
                    null,
                    message,
                    false,
                    compatibilityUnverified);
        }

        private static RecoverableMutationAttempt unsafe(
                String message, boolean compatibilityUnverified) {
            return new RecoverableMutationAttempt(
                    ServiceResult.failure(message),
                    null,
                    null,
                    message,
                    true,
                    compatibilityUnverified);
        }

        private static RecoverableMutationAttempt recoverable(
                PreparedRuleMutation plan,
                RuleStoreSnapshot failedSnapshot,
                String message) {
            return new RecoverableMutationAttempt(
                    ServiceResult.failure(message),
                    Objects.requireNonNull(plan, "plan"),
                    Objects.requireNonNull(failedSnapshot, "failedSnapshot"),
                    message,
                    false,
                    plan.compatibilityUnverified());
        }

        ServiceResult<RuleMutationResult> result() {
            return result;
        }

        PreparedRuleMutation plan() {
            return plan;
        }

        RuleStoreSnapshot failedSnapshot() {
            return failedSnapshot;
        }

        String failureReason() {
            return failureReason;
        }

        boolean recoverable() {
            return plan != null;
        }

        boolean unsafe() {
            return unsafe;
        }

        boolean compatibilityUnverified() {
            return compatibilityUnverified;
        }
    }

    static final class RecoverableWriteAttempt {
        private final ServiceResult<RuleCreationResult> result;
        private final PreparedRuleWrite plan;
        private final RuleStoreSnapshot failedSnapshot;
        private final String failureReason;
        private final boolean unsafe;
        private final boolean compatibilityUnverified;

        private RecoverableWriteAttempt(
                ServiceResult<RuleCreationResult> result,
                PreparedRuleWrite plan,
                RuleStoreSnapshot failedSnapshot,
                String failureReason,
                boolean unsafe,
                boolean compatibilityUnverified) {
            this.result = Objects.requireNonNull(result, "result");
            this.plan = plan;
            this.failedSnapshot = failedSnapshot;
            this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
            this.unsafe = unsafe;
            this.compatibilityUnverified = compatibilityUnverified;
        }

        private static RecoverableWriteAttempt success(RuleCreationResult result) {
            return new RecoverableWriteAttempt(
                    ServiceResult.success(result),
                    null,
                    null,
                    "",
                    false,
                    result.compatibilityUnverified());
        }

        private static RecoverableWriteAttempt failed(String message) {
            return new RecoverableWriteAttempt(
                    ServiceResult.failure(message), null, null, message, false, false);
        }

        private static RecoverableWriteAttempt failed(
                String message, boolean compatibilityUnverified) {
            return new RecoverableWriteAttempt(
                    ServiceResult.failure(message),
                    null,
                    null,
                    message,
                    false,
                    compatibilityUnverified);
        }

        private static RecoverableWriteAttempt unsafe(
                String message, boolean compatibilityUnverified) {
            return new RecoverableWriteAttempt(
                    ServiceResult.failure(message),
                    null,
                    null,
                    message,
                    true,
                    compatibilityUnverified);
        }

        private static RecoverableWriteAttempt recoverable(
                PreparedRuleWrite plan,
                RuleStoreSnapshot failedSnapshot,
                String message) {
            return new RecoverableWriteAttempt(
                    ServiceResult.failure(message),
                    Objects.requireNonNull(plan, "plan"),
                    Objects.requireNonNull(failedSnapshot, "failedSnapshot"),
                    message,
                    false,
                    plan.compatibilityUnverified());
        }

        boolean recoverable() {
            return plan != null;
        }

        boolean unsafe() {
            return unsafe;
        }

        boolean compatibilityUnverified() {
            return compatibilityUnverified;
        }

        ServiceResult<RuleCreationResult> result() {
            return result;
        }

        PreparedRuleWrite plan() {
            return plan;
        }

        RuleStoreSnapshot failedSnapshot() {
            return failedSnapshot;
        }

        String failureReason() {
            return failureReason;
        }
    }

    static final class RecoveryInspection {
        private final boolean canRetry;
        private final boolean canRollback;
        private final String message;

        RecoveryInspection(boolean canRetry, boolean canRollback, String message) {
            this.canRetry = canRetry;
            this.canRollback = canRollback;
            this.message = Objects.requireNonNull(message, "message");
        }

        boolean canRetry() {
            return canRetry;
        }

        boolean canRollback() {
            return canRollback;
        }

        String message() {
            return message;
        }
    }

    static final class RecoveryRetryAttempt {
        private final boolean writeAttempted;
        private final RuleCreationResult result;
        private final String message;
        private final boolean readOnlyFault;

        private RecoveryRetryAttempt(
                boolean writeAttempted,
                RuleCreationResult result,
                String message,
                boolean readOnlyFault) {
            this.writeAttempted = writeAttempted;
            this.result = result;
            this.message = Objects.requireNonNull(message, "message");
            this.readOnlyFault = readOnlyFault;
        }

        static RecoveryRetryAttempt succeeded(RuleCreationResult result) {
            return new RecoveryRetryAttempt(
                    true, Objects.requireNonNull(result, "result"), "", false);
        }

        static RecoveryRetryAttempt notAttempted(String message) {
            return new RecoveryRetryAttempt(false, null, message, false);
        }

        static RecoveryRetryAttempt failed(String message, boolean readOnlyFault) {
            return new RecoveryRetryAttempt(true, null, message, readOnlyFault);
        }

        boolean writeAttempted() {
            return writeAttempted;
        }

        boolean success() {
            return result != null;
        }

        RuleCreationResult result() {
            return result;
        }

        String message() {
            return message;
        }

        boolean readOnlyFault() {
            return readOnlyFault;
        }
    }

    static final class RecoveryMutationRetryAttempt {
        private final boolean writeAttempted;
        private final RuleMutationResult result;
        private final String message;
        private final boolean readOnlyFault;

        private RecoveryMutationRetryAttempt(
                boolean writeAttempted,
                RuleMutationResult result,
                String message,
                boolean readOnlyFault) {
            this.writeAttempted = writeAttempted;
            this.result = result;
            this.message = Objects.requireNonNull(message, "message");
            this.readOnlyFault = readOnlyFault;
        }

        static RecoveryMutationRetryAttempt succeeded(RuleMutationResult result) {
            return new RecoveryMutationRetryAttempt(
                    true, Objects.requireNonNull(result, "result"), "", false);
        }

        static RecoveryMutationRetryAttempt notAttempted(String message) {
            return new RecoveryMutationRetryAttempt(false, null, message, false);
        }

        static RecoveryMutationRetryAttempt failed(
                String message, boolean readOnlyFault) {
            return new RecoveryMutationRetryAttempt(
                    true, null, message, readOnlyFault);
        }

        boolean writeAttempted() {
            return writeAttempted;
        }

        boolean success() {
            return result != null;
        }

        RuleMutationResult result() {
            return result;
        }

        String message() {
            return message;
        }

        boolean readOnlyFault() {
            return readOnlyFault;
        }
    }

    static final class RecoveryRollbackAttempt {
        private final boolean verified;
        private final String message;

        private RecoveryRollbackAttempt(boolean verified, String message) {
            this.verified = verified;
            this.message = Objects.requireNonNull(message, "message");
        }

        static RecoveryRollbackAttempt success() {
            return new RecoveryRollbackAttempt(true, "");
        }

        static RecoveryRollbackAttempt failed(String message) {
            return new RecoveryRollbackAttempt(false, message);
        }

        boolean verified() {
            return verified;
        }

        String message() {
            return message;
        }
    }

    @FunctionalInterface
    private interface RuleFactory {
        NotificationRule create(
                SourceDevice source,
                TargetDevice target,
                List<NotificationRule> currentRules);
    }
}
