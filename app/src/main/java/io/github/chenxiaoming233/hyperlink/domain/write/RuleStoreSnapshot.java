package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RuleStoreSnapshot {
    private final NotificationFlowSnapshot flowSnapshot;
    private final SourceDevice sourceDevice;
    private final CapabilityEvidence capabilities;
    private final boolean unknownFieldsPresent;
    private final boolean unexpressibleDslPresent;
    private final String serializedJson;

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
                RuleSerialization.serialize(flowSnapshot.rules()));
    }

    private RuleStoreSnapshot(
            NotificationFlowSnapshot flowSnapshot,
            SourceDevice sourceDevice,
            CapabilityEvidence capabilities,
            boolean unknownFieldsPresent,
            boolean unexpressibleDslPresent,
            String serializedJson) {
        this.flowSnapshot = Objects.requireNonNull(flowSnapshot, "flowSnapshot");
        this.sourceDevice = Objects.requireNonNull(sourceDevice, "sourceDevice");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.unknownFieldsPresent = unknownFieldsPresent;
        this.unexpressibleDslPresent = unexpressibleDslPresent;
        this.serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
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
                RuleSerialization.serialize(copy));
    }

    public RuleStoreSnapshot withSafetyFlags(
            boolean nextUnknownFieldsPresent, boolean nextUnexpressibleDslPresent) {
        return new RuleStoreSnapshot(
                flowSnapshot,
                sourceDevice,
                capabilities,
                nextUnknownFieldsPresent,
                nextUnexpressibleDslPresent,
                serializedJson);
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
