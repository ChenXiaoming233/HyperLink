package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AppConditionEditorModel {
    private final StructuredRuleDraft draft;
    private final int groupIndex;
    private final int branchIndex;
    private final int appConditionIndex;
    private final List<String> selectedPackages;
    private final RuleCondition.Operator operator;

    private AppConditionEditorModel(
            StructuredRuleDraft draft,
            int groupIndex,
            int branchIndex,
            int appConditionIndex,
            List<String> selectedPackages,
            RuleCondition.Operator operator) {
        this.draft = Objects.requireNonNull(draft, "draft");
        this.groupIndex = groupIndex;
        this.branchIndex = branchIndex;
        this.appConditionIndex = appConditionIndex;
        this.selectedPackages = immutablePackages(selectedPackages);
        this.operator = requireAppOperator(operator);
        validateScope();
    }

    public static AppConditionEditorModel atCondition(
            StructuredRuleDraft draft,
            int groupIndex,
            int branchIndex,
            int conditionIndex) {
        RuleCondition condition = conditionAt(draft, groupIndex, branchIndex, conditionIndex);
        if (condition.field() != RuleCondition.Field.APP) {
            throw new IllegalArgumentException("Selected condition is not an app condition");
        }
        return new AppConditionEditorModel(
                draft,
                groupIndex,
                branchIndex,
                conditionIndex,
                condition.appPackages(),
                condition.operator());
    }

    public static AppConditionEditorModel atBranch(
            StructuredRuleDraft draft, int groupIndex, int branchIndex) {
        RuleBranch branch = branchAt(draft, groupIndex, branchIndex);
        for (int index = 0; index < branch.conditions().size(); index++) {
            RuleCondition condition = branch.conditions().get(index);
            if (condition.field() == RuleCondition.Field.APP) {
                return atCondition(draft, groupIndex, branchIndex, index);
            }
        }
        return new AppConditionEditorModel(
                draft,
                groupIndex,
                branchIndex,
                -1,
                Collections.emptyList(),
                RuleCondition.Operator.IN);
    }

    public static AppConditionEditorModel firstEditable(StructuredRuleDraft draft) {
        Objects.requireNonNull(draft, "draft");
        for (int groupIndex = 0; groupIndex < draft.groups().size(); groupIndex++) {
            RuleAndGroup group = draft.groups().get(groupIndex);
            for (int branchIndex = 0; branchIndex < group.branches().size(); branchIndex++) {
                RuleBranch branch = group.branches().get(branchIndex);
                for (int conditionIndex = 0;
                        conditionIndex < branch.conditions().size();
                        conditionIndex++) {
                    if (branch.conditions().get(conditionIndex).field()
                            == RuleCondition.Field.APP) {
                        return atCondition(draft, groupIndex, branchIndex, conditionIndex);
                    }
                }
            }
        }
        if (!draft.groups().isEmpty()
                && !draft.groups().get(0).branches().isEmpty()) {
            return atBranch(draft, 0, 0);
        }
        return null;
    }

    public StructuredRuleDraft draft() {
        return draft;
    }

    public int groupIndex() {
        return groupIndex;
    }

    public int branchIndex() {
        return branchIndex;
    }

    public int appConditionIndex() {
        return appConditionIndex;
    }

    public List<String> selectedPackages() {
        return selectedPackages;
    }

    public RuleCondition.Operator operator() {
        return operator;
    }

    public AppConditionEditorModel togglePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName must not be blank");
        }
        Set<String> packages = new LinkedHashSet<>(selectedPackages);
        if (!packages.remove(packageName)) {
            packages.add(packageName);
        }
        return new AppConditionEditorModel(
                draft,
                groupIndex,
                branchIndex,
                appConditionIndex,
                new ArrayList<>(packages),
                operator);
    }

    public AppConditionEditorModel apply(RuleCondition.Operator nextOperator) {
        RuleCondition.Operator appliedOperator = requireAppOperator(nextOperator);
        if (selectedPackages.isEmpty()) {
            throw new IllegalStateException("Select at least one app before applying");
        }
        RuleAndGroup group = draft.groups().get(groupIndex);
        RuleBranch branch = group.branches().get(branchIndex);
        int appliedConditionIndex = appConditionIndex;
        RuleBranch updatedBranch;
        if (appConditionIndex >= 0) {
            RuleCondition existing = branch.conditions().get(appConditionIndex);
            updatedBranch =
                    branch.replaceCondition(
                            appConditionIndex,
                            existing.withOperator(appliedOperator)
                                    .withAppPackages(selectedPackages));
        } else {
            appliedConditionIndex = branch.conditions().size();
            RuleCondition added =
                    appliedOperator == RuleCondition.Operator.IN
                            ? RuleCondition.appIn(selectedPackages)
                            : RuleCondition.appNotIn(selectedPackages);
            updatedBranch = branch.addCondition(added);
        }
        StructuredRuleDraft updatedDraft =
                draft.replaceGroup(
                        groupIndex,
                        group.replaceBranch(branchIndex, updatedBranch));
        return new AppConditionEditorModel(
                updatedDraft,
                groupIndex,
                branchIndex,
                appliedConditionIndex,
                selectedPackages,
                appliedOperator);
    }

    private void validateScope() {
        branchAt(draft, groupIndex, branchIndex);
        if (appConditionIndex < -1) {
            throw new IllegalArgumentException("appConditionIndex must be -1 or greater");
        }
        if (appConditionIndex >= 0) {
            conditionAt(draft, groupIndex, branchIndex, appConditionIndex);
        }
    }

    private static RuleBranch branchAt(
            StructuredRuleDraft draft, int groupIndex, int branchIndex) {
        Objects.requireNonNull(draft, "draft");
        if (groupIndex < 0 || groupIndex >= draft.groups().size()) {
            throw new IllegalArgumentException("Unknown group index " + groupIndex);
        }
        RuleAndGroup group = draft.groups().get(groupIndex);
        if (branchIndex < 0 || branchIndex >= group.branches().size()) {
            throw new IllegalArgumentException("Unknown branch index " + branchIndex);
        }
        return group.branches().get(branchIndex);
    }

    private static RuleCondition conditionAt(
            StructuredRuleDraft draft,
            int groupIndex,
            int branchIndex,
            int conditionIndex) {
        RuleBranch branch = branchAt(draft, groupIndex, branchIndex);
        if (conditionIndex < 0 || conditionIndex >= branch.conditions().size()) {
            throw new IllegalArgumentException("Unknown condition index " + conditionIndex);
        }
        return branch.conditions().get(conditionIndex);
    }

    private static RuleCondition.Operator requireAppOperator(
            RuleCondition.Operator operator) {
        Objects.requireNonNull(operator, "operator");
        if (operator != RuleCondition.Operator.IN
                && operator != RuleCondition.Operator.NOT_IN) {
            throw new IllegalArgumentException("App conditions require in or not_in");
        }
        return operator;
    }

    private static List<String> immutablePackages(List<String> values) {
        Objects.requireNonNull(values, "selectedPackages");
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be blank");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}
