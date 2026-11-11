package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class StructuredRuleDraft {
    private final String ruleId;
    private final String status;
    private final long createdAt;
    private final List<RuleAndGroup> groups;

    private StructuredRuleDraft(
            String ruleId,
            String status,
            long createdAt,
            List<RuleAndGroup> groups) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.status = requireStatus(status);
        if (createdAt < 0L) {
            throw new IllegalArgumentException("createdAt must not be negative");
        }
        this.createdAt = createdAt;
        this.groups = immutableGroups(groups);
    }

    public static StructuredRuleDraft empty(long createdAt) {
        return new StructuredRuleDraft(
                "", "active", createdAt, Collections.emptyList());
    }

    public static StructuredRuleDraft fromPresets(
            List<RulePreset> presets,
            List<String> imPackages,
            long createdAt) {
        Objects.requireNonNull(presets, "presets");
        if (presets.isEmpty()) {
            throw new IllegalArgumentException("At least one preset is required");
        }
        Set<RulePreset> uniquePresets = new LinkedHashSet<>(presets);
        List<RuleBranch> branches = new ArrayList<>();
        for (RulePreset preset : uniquePresets) {
            branches.add(preset.toBranch(imPackages));
        }
        return new StructuredRuleDraft(
                "",
                "active",
                createdAt,
                Collections.singletonList(RuleAndGroup.of(branches)));
    }

    public static StructuredRuleDraft fromParsed(
            String ruleId, String status, long createdAt, List<RuleAndGroup> groups) {
        return new StructuredRuleDraft(ruleId, status, createdAt, groups);
    }

    public String ruleId() {
        return ruleId;
    }

    public boolean hasRuleId() {
        return !ruleId.isEmpty();
    }

    public StructuredRuleDraft withRuleId(String nextRuleId) {
        return new StructuredRuleDraft(
                Objects.requireNonNull(nextRuleId, "nextRuleId"),
                status,
                createdAt,
                groups);
    }

    public String status() {
        return status;
    }

    public StructuredRuleDraft withStatus(String nextStatus) {
        return new StructuredRuleDraft(ruleId, nextStatus, createdAt, groups);
    }

    public long createdAt() {
        return createdAt;
    }

    public List<RuleAndGroup> groups() {
        return groups;
    }

    public StructuredRuleDraft addGroup(RuleAndGroup group) {
        List<RuleAndGroup> next = new ArrayList<>(groups);
        next.add(Objects.requireNonNull(group, "group"));
        return new StructuredRuleDraft(ruleId, status, createdAt, next);
    }

    public StructuredRuleDraft removeGroup(int index) {
        List<RuleAndGroup> next = new ArrayList<>(groups);
        next.remove(RuleBranch.checkedIndex(index, next.size()));
        return new StructuredRuleDraft(ruleId, status, createdAt, next);
    }

    public StructuredRuleDraft moveGroup(int fromIndex, int toIndex) {
        List<RuleAndGroup> next = new ArrayList<>(groups);
        RuleBranch.move(next, fromIndex, toIndex);
        return new StructuredRuleDraft(ruleId, status, createdAt, next);
    }

    public StructuredRuleDraft replaceGroup(int index, RuleAndGroup group) {
        List<RuleAndGroup> next = new ArrayList<>(groups);
        next.set(
                RuleBranch.checkedIndex(index, next.size()),
                Objects.requireNonNull(group, "group"));
        return new StructuredRuleDraft(ruleId, status, createdAt, next);
    }

    public String summary() {
        return RuleSummaryGenerator.generate(groups);
    }

    public Set<String> referencedPackages() {
        Set<String> packages = new LinkedHashSet<>();
        for (RuleAndGroup group : groups) {
            for (RuleBranch branch : group.branches()) {
                for (RuleCondition condition : branch.conditions()) {
                    if (condition.field() == RuleCondition.Field.APP) {
                        packages.addAll(condition.appPackages());
                    }
                }
            }
        }
        return Collections.unmodifiableSet(packages);
    }

    public boolean canSave() {
        return !groups.isEmpty();
    }

    private static List<RuleAndGroup> immutableGroups(List<RuleAndGroup> values) {
        Objects.requireNonNull(values, "groups");
        List<RuleAndGroup> copy = new ArrayList<>(values.size());
        for (RuleAndGroup value : values) {
            copy.add(Objects.requireNonNull(value, "group"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireStatus(String value) {
        Objects.requireNonNull(value, "status");
        if (!"active".equals(value) && !"paused".equals(value)) {
            throw new IllegalArgumentException("status must be active or paused");
        }
        return value;
    }
}
