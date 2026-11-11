package io.github.chenxiaoming233.hyperlink.domain.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.write.StructuredRuleDsl;
import java.util.List;
import org.junit.Test;

public final class StructuredRuleEditorTest {
    @Test
    public void presetsExpandToOrBranchesWithoutStoredPresetType() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(
                                RulePreset.IM,
                                RulePreset.FOCUSED_NOTIFICATION,
                                RulePreset.VERIFICATION_CODE),
                        List.of("demo.app.chat", "demo.app.social"),
                        100L);

        assertEquals(1, draft.groups().size());
        assertEquals(3, draft.groups().get(0).branches().size());
        assertEquals(
                RuleCondition.Field.APP,
                draft.groups().get(0).branches().get(0).conditions().get(0).field());
        assertEquals(
                RuleCondition.Operator.IN,
                draft.groups().get(0).branches().get(0).conditions().get(0).operator());
        assertEquals(
                List.of("demo.app.chat", "demo.app.social"),
                draft.groups().get(0).branches().get(0).conditions().get(0).appPackages());

        String dsl = StructuredRuleDsl.serialize(draft);
        assertTrue(dsl.contains("\"andGroups\""));
        assertTrue(dsl.contains("\"branches\""));
        assertFalse(dsl.contains("presetType"));
        assertFalse(dsl.contains("mode"));
    }

    @Test
    public void supportsGroupBranchAndConditionAddRemoveReorderAndEdit() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION),
                        List.of(),
                        100L);
        RuleBranch appBranch =
                RuleBranch.of(
                        RuleCondition.appNotIn(
                                List.of("demo.app.blocked-a", "demo.app.blocked-b")),
                        RuleCondition.isVerifyCode(false));
        RuleAndGroup group = draft.groups().get(0).addBranch(appBranch);
        group =
                group.replaceBranch(
                        0, group.branches().get(0).addCondition(RuleCondition.isIsland(true)));
        group =
                group.replaceBranch(
                        0, group.branches().get(0).moveCondition(0, 1));
        group = group.moveBranch(1, 0);
        draft = draft.replaceGroup(0, group);

        assertEquals(RuleCondition.Operator.NOT_IN, draft.groups().get(0)
                .branches().get(0).conditions().get(0).operator());
        assertEquals(List.of("demo.app.blocked-a", "demo.app.blocked-b"),
                draft.groups().get(0).branches().get(0).conditions().get(0).appPackages());
        assertEquals(RuleCondition.Field.ISLAND, draft.groups().get(0)
                .branches().get(1).conditions().get(1).field());
        String editedDsl = StructuredRuleDsl.serialize(draft);
        assertTrue(editedDsl.contains("\"op\":\"not_in\""));
        assertTrue(editedDsl.contains("\"field\":\"isIsland\""));
        assertTrue(editedDsl.contains("\"value\":\"false\""));

        group = draft.groups().get(0).removeBranch(1);
        draft = draft.replaceGroup(0, group);
        group = draft.groups().get(0).addBranch(RuleBranch.of(RuleCondition.isVerifyCode(true)));
        draft = draft.addGroup(RuleAndGroup.of(group.branches().get(1)));
        draft = draft.moveGroup(1, 0);

        assertEquals(2, draft.groups().size());
        assertEquals(RuleCondition.Field.VERIFY_CODE, draft.groups().get(0)
                .branches().get(0).conditions().get(0).field());
    }

    @Test
    public void filterChangesRegenerateSummaryButStatusChangesDoNot() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.IM),
                        List.of("demo.app.chat"),
                        100L);
        String initialSummary = draft.summary();
        StructuredRuleDraft paused = draft.withStatus("paused");

        assertEquals(initialSummary, paused.summary());
        assertEquals("paused", paused.status());

        RuleBranch changedBranch =
                RuleBranch.of(
                        RuleCondition.appIn(List.of("demo.app.chat", "demo.app.social")),
                        RuleCondition.isIsland(true));
        StructuredRuleDraft changed =
                paused.replaceGroup(0, RuleAndGroup.of(changedBranch));

        assertNotEquals(initialSummary, changed.summary());
        assertTrue(changed.summary().contains("demo.app.social"));
        assertTrue(changed.summary().contains("焦点通知"));
    }

    @Test
    public void parsesExistingRuleStructureAndPreservesEditMetadata() {
        NotificationRule existing =
                new NotificationRule(
                        "demo-rule-existing",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "paused",
                        "旧摘要",
                        "{\"andGroups\":[{\"branches\":[["
                                + "{\"field\":\"app\",\"op\":\"not_in\",\"value\":"
                                + "\"[\\\"demo.app.blocked\\\"]\"},"
                                + "{\"field\":\"isIsland\",\"op\":\"is\",\"value\":\"false\"}"
                                + "]]}],\"ruleText\":\"旧摘要\"}",
                        123456789L);

        StructuredRuleDraft parsed = StructuredRuleDsl.parse(existing);

        assertEquals("demo-rule-existing", parsed.ruleId());
        assertEquals("paused", parsed.status());
        assertEquals(123456789L, parsed.createdAt());
        assertEquals(
                RuleCondition.Operator.NOT_IN,
                parsed.groups().get(0).branches().get(0).conditions().get(0).operator());
        assertEquals(
                List.of("demo.app.blocked"),
                parsed.groups().get(0).branches().get(0).conditions().get(0).appPackages());
        assertFalse(parsed.groups().get(0).branches().get(0).conditions().get(1).booleanValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void imPresetRequiresAtLeastOneAppPackage() {
        StructuredRuleDraft.fromPresets(
                List.of(RulePreset.IM), List.of(), 100L);
    }
}
