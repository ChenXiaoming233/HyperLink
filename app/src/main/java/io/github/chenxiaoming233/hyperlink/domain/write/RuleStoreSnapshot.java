package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuleStoreSnapshot {
    private final NotificationFlowSnapshot flowSnapshot;
    private final SourceDevice sourceDevice;
    private final CapabilityEvidence capabilities;
    private final boolean unknownFieldsPresent;
    private final boolean unexpressibleDslPresent;
    private final String serializedJson;
    private final String readOnlyFaultMessage;

    public RuleStoreSnapshot(
            NotificationFlowSnapshot flowSnapshot,
            SourceDevice sourceDevice,
            CapabilityEvidence capabilities,
            boolean unknownFieldsPresent,
            boolean unexpressibleDslPresent) {
        this(
                flowSnapshot,
                sourceDevice,
                capabilities,
                unknownFieldsPresent,
                unexpressibleDslPresent,
                RuleSerialization.serialize(flowSnapshot.rules()),
                "");
    }

    private RuleStoreSnapshot(
            NotificationFlowSnapshot flowSnapshot,
            SourceDevice sourceDevice,
            CapabilityEvidence capabilities,
            boolean unknownFieldsPresent,
            boolean unexpressibleDslPresent,
            String serializedJson,
            String readOnlyFaultMessage) {
        this.flowSnapshot = Objects.requireNonNull(flowSnapshot, "flowSnapshot");
        this.sourceDevice = Objects.requireNonNull(sourceDevice, "sourceDevice");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.unknownFieldsPresent = unknownFieldsPresent;
        this.unexpressibleDslPresent = unexpressibleDslPresent;
        this.serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
        this.readOnlyFaultMessage =
                Objects.requireNonNull(readOnlyFaultMessage, "readOnlyFaultMessage");
    }

    public NotificationFlowSnapshot flowSnapshot() {
        return flowSnapshot;
    }

    public SourceDevice sourceDevice() {
        return sourceDevice;
    }

    public CapabilityEvidence capabilities() {
        return capabilities;
    }

    public List<NotificationRule> rules() {
        return flowSnapshot.rules();
    }

    public boolean unknownFieldsPresent() {
        return unknownFieldsPresent;
    }

    public boolean unexpressibleDslPresent() {
        return unexpressibleDslPresent;
    }

    public String serializedJson() {
        return serializedJson;
    }

    public String readOnlyFaultMessage() {
        return readOnlyFaultMessage;
    }

    public RuleStoreSnapshot withRules(List<NotificationRule> rules) {
        List<NotificationRule> copy = immutableCopy(rules);
        return new RuleStoreSnapshot(
                new NotificationFlowSnapshot(
                        flowSnapshot.connectionState(),
                        flowSnapshot.stateVersion() + 1L,
                        flowSnapshot.compatibilityUnverified(),
                        flowSnapshot.targets(),
                        copy,
                        flowSnapshot.notificationAccess(),
                        flowSnapshot.staleCache()),
                sourceDevice,
                capabilities,
                unknownFieldsPresent,
                unexpressibleDslPresent,
                RuleSerialization.serialize(copy),
                readOnlyFaultMessage);
    }

    public RuleStoreSnapshot withSafetyFlags(
            boolean nextUnknownFieldsPresent, boolean nextUnexpressibleDslPresent) {
        return new RuleStoreSnapshot(
                flowSnapshot,
                sourceDevice,
                capabilities,
                nextUnknownFieldsPresent,
                nextUnexpressibleDslPresent,
                serializedJson,
                readOnlyFaultMessage);
    }

    public RuleStoreSnapshot withPermissions(
            Map<String, Boolean> targetAuthorizations,
            NotificationAccessState nextNotificationAccess) {
        Objects.requireNonNull(targetAuthorizations, "targetAuthorizations");
        Objects.requireNonNull(nextNotificationAccess, "nextNotificationAccess");
        List<TargetDevice> targets = new ArrayList<>(flowSnapshot.targets().size());
        for (TargetDevice target : flowSnapshot.targets()) {
            Boolean authorized = targetAuthorizations.get(target.deviceId());
            if (authorized == null) {
                throw new IllegalArgumentException(
                        "Missing permission for " + target.deviceId());
            }
            targets.add(
                    new TargetDevice(
                            target.deviceId(),
                            target.displayName(),
                            target.type(),
                            target.online(),
                            target.reachable(),
                            authorized,
                            target.receiveCapability()));
        }
        NotificationFlowSnapshot changedFlow =
                new NotificationFlowSnapshot(
                        flowSnapshot.connectionState(),
                        flowSnapshot.stateVersion() + 1L,
                        flowSnapshot.compatibilityUnverified(),
                        targets,
                        flowSnapshot.rules(),
                        nextNotificationAccess,
                        flowSnapshot.staleCache());
        return new RuleStoreSnapshot(
                changedFlow,
                sourceDevice,
                capabilities,
                unknownFieldsPresent,
                unexpressibleDslPresent,
                serializedJson,
                readOnlyFaultMessage);
    }

    public RuleStoreSnapshot withReadOnlyFault(String message) {
        return new RuleStoreSnapshot(
                flowSnapshot,
                sourceDevice,
                capabilities,
                unknownFieldsPresent,
                unexpressibleDslPresent,
                serializedJson,
                Objects.requireNonNull(message, "message"));
    }

    private static List<NotificationRule> immutableCopy(List<NotificationRule> rules) {
        Objects.requireNonNull(rules, "rules");
        ArrayList<NotificationRule> copy = new ArrayList<>(rules.size());
        for (NotificationRule rule : rules) {
            copy.add(Objects.requireNonNull(rule, "rule"));
        }
        return Collections.unmodifiableList(copy);
    }
}
