package io.github.chenxiaoming233.hyperlink.domain.editor;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class AppConditionEditorModelTest {
    @Test
    public void editingOneConditionDoesNotChangeAnotherBranch() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromParsed(
                        "demo-rule-scoped",
                        "active",
                        100L,
                        List.of(
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.appIn(List.of("demo.app.a"))),
                                        RuleBranch.of(
                                                RuleCondition.appIn(List.of("demo.app.b"))))));

        AppConditionEditorModel first =
                AppConditionEditorModel.atCondition(draft, 0, 0, 0);
        assertEquals(List.of("demo.app.a"), first.selectedPackages());

        AppConditionEditorModel edited =
                first.togglePackage("demo.app.c").apply(RuleCondition.Operator.IN);

        assertEquals(
                List.of("demo.app.a", "demo.app.c"),
                edited.draft().groups().get(0).branches().get(0).conditions().get(0).appPackages());
        assertEquals(
                List.of("demo.app.b"),
                edited.draft().groups().get(0).branches().get(1).conditions().get(0).appPackages());
    }

    @Test
    public void switchingConditionRefreshesSelectionToThatConditionsPackages() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromParsed(
                        "demo-rule-scoped",
                        "active",
                        100L,
                        List.of(
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.appIn(List.of("demo.app.a"))),
                                        RuleBranch.of(
                                                RuleCondition.appNotIn(List.of("demo.app.b"))))));

        AppConditionEditorModel second =
                AppConditionEditorModel.atCondition(draft, 0, 1, 0);

        assertEquals(List.of("demo.app.b"), second.selectedPackages());
        assertEquals(RuleCondition.Operator.NOT_IN, second.operator());
    }
}
