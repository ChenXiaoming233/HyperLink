package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

public final class SafeNotificationRuleWriter {
    private static final Object PROCESS_WRITE_LOCK = new Object();
    private static final Map<RuleStoreGateway, String> PROCESS_FAULTS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    private ServiceResult<RuleCreationResult> createAllNotificationsRuleLocked(
            ConfirmedTargetSelection selection) {
        if (selection == null) {
            return ServiceResult.failure("未确认目标，未执行任何写入。");
        }
        String processFault = readOnlyFaultMessageLocked();
        if (!processFault.isEmpty()) {
            return ServiceResult.failure(processFault);
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
        if (current.unknownFieldsPresent()) {
            return enterReadOnlyFaultLocked(
                    "规则快照包含未知字段，为避免部分写入，当前规则写入保持只读。");
        }
        if (current.unexpressibleDslPresent()) {
            return enterReadOnlyFaultLocked(
                    "规则快照包含不可表达 DSL，为避免部分写入，当前规则写入保持只读。");
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
            createdRule = createRule(current.sourceDevice(), target, current.rules());
            candidate = upsert(current.rules(), createdRule);
            expectedSerialized = RuleSerialization.serialize(candidate);
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法生成唯一规则标识，未执行任何写入。");
        }

        ServiceResult<RuleWriteReceipt> writeResult;
        try {
            writeResult = gateway.write(candidate);
        } catch (RuntimeException exception) {
            return failAndRollback(lkg, "规则写入异常，");
        }
        if (!writeResult.isSuccess()) {
            return failAndRollback(lkg, "规则写入未完成，");
        }

        ServiceResult<RuleStoreSnapshot> readBack;
        try {
            readBack = gateway.read();
        } catch (RuntimeException exception) {
            return failAndRollback(lkg, "写入后回读异常，");
        }
        if (!readBack.isSuccess()) {
            return failAndRollback(lkg, "写入后无法回读规则，");
        }
        RuleStoreSnapshot verified = readBack.value().orElseThrow();
        if (hasUnsafeStructure(verified)
                || !writeResult.value().orElseThrow().serializedJson().equals(expectedSerialized)
                || !verified.serializedJson().equals(expectedSerialized)
                || !matchesRules(candidate, verified.rules())
                || !uniqueRuleIds(candidate)
                || matchingTargetCount(candidate, createdRule) != 1
                || !targetRuleMatches(candidate, createdRule)) {
            return failAndRollback(lkg, "写入后校验失败，");
        }

        List<String> notes = new ArrayList<>();
        notes.add(systemGateNotice(flow.notificationAccess()));
        notes.add(targetCapabilityNotice(target));
        if (!selection.offlineWarning().isEmpty()) {
            notes.add(selection.offlineWarning());
        }
        if (capability.compatibilityUnverified()) {
            notes.add("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return ServiceResult.success(
                new RuleCreationResult(
                        createdRule, lkg, capability.compatibilityUnverified(), notes));
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

    private ServiceResult<RuleCreationResult> failAndRollback(
            RuleLkg lkg, String failurePrefix) {
        boolean rollbackVerified = rollbackAndVerify(lkg);
        if (!rollbackVerified) {
            return enterReadOnlyFaultLocked(
                    failurePrefix + "无法验证回滚，当前规则写入保持只读。");
        }
        return ServiceResult.failure(failurePrefix + "已从原始 LKG 回滚并验证。");
    }

    private boolean rollbackAndVerify(RuleLkg lkg) {
        ServiceResult<RuleWriteReceipt> rollbackWrite;
        try {
            rollbackWrite = gateway.write(lkg.rules());
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
                && verified.serializedJson().equals(lkg.serializedJson())
                && matchesRules(lkg.rules(), verified.rules())
                && uniqueRuleIds(verified.rules());
    }

    private ServiceResult<RuleCreationResult> enterReadOnlyFaultLocked(String message) {
        PROCESS_FAULTS.put(gateway, message);
        return ServiceResult.failure(message);
    }

    private String readOnlyFaultMessageLocked() {
        String message = PROCESS_FAULTS.get(gateway);
        return message == null ? "" : message;
    }

    private static boolean hasUnsafeStructure(RuleStoreSnapshot snapshot) {
        return snapshot.unknownFieldsPresent()
                || snapshot.unexpressibleDslPresent()
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
                dsl);
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
                && left.dslJson().equals(right.dslJson());
    }
}
