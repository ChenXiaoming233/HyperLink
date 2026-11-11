package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RuleMutationResult {
    private final NotificationRule rule;
    private final String affectedTargetDeviceId;
    private final RuleStoreSnapshot snapshot;
    private final RuleLkg lkg;
    private final boolean compatibilityUnverified;
    private final List<String> notes;

    RuleMutationResult(
            NotificationRule rule,
            String affectedTargetDeviceId,
            RuleStoreSnapshot snapshot,
            RuleLkg lkg,
            boolean compatibilityUnverified,
            List<String> notes) {
        this.rule = rule;
        this.affectedTargetDeviceId =
                Objects.requireNonNull(affectedTargetDeviceId, "affectedTargetDeviceId");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lkg = Objects.requireNonNull(lkg, "lkg");
        this.compatibilityUnverified = compatibilityUnverified;
        this.notes = immutableCopy(notes);
    }

    public Optional<NotificationRule> rule() {
        return Optional.ofNullable(rule);
    }

    public boolean deleted() {
        return rule == null;
    }

    public String affectedTargetDeviceId() {
        return affectedTargetDeviceId;
    }

    public RuleStoreSnapshot snapshot() {
        return snapshot;
    }

    public RuleLkg lkg() {
        return lkg;
    }

    public boolean compatibilityUnverified() {
        return compatibilityUnverified;
    }

    public List<String> notes() {
        return notes;
    }

    private static List<String> immutableCopy(List<String> values) {
        Objects.requireNonNull(values, "notes");
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(Objects.requireNonNull(value, "note"));
        }
        return Collections.unmodifiableList(copy);
    }
}
