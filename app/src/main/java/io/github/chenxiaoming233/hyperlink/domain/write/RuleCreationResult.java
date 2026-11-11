package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RuleCreationResult {
    private final NotificationRule rule;
    private final RuleLkg lkg;
    private final boolean compatibilityUnverified;
    private final List<String> notes;

    RuleCreationResult(
            NotificationRule rule,
            RuleLkg lkg,
            boolean compatibilityUnverified,
            List<String> notes) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.lkg = Objects.requireNonNull(lkg, "lkg");
        this.compatibilityUnverified = compatibilityUnverified;
        this.notes = immutableCopy(notes);
    }

    public NotificationRule rule() {
        return rule;
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
