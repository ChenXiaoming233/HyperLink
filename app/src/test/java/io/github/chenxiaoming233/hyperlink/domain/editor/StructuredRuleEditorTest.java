package io.github.chenxiaoming233.hyperlink.domain.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void validatesSenderAndTextRegexWithSafeMessages() {
        assertEquals("", RuleCondition.regexIssue("^acme-[0-9]{4}$"));

        RuleCondition sender = RuleCondition.senderRegex("^acme-[0-9]{4}$");
        RuleCondition text = RuleCondition.textNotRegex("(?i)(广告|推广)");

        assertEquals(RuleCondition.Field.SENDER, sender.field());
        assertEquals(RuleCondition.Operator.REGEX, sender.operator());
        assertEquals("^acme-[0-9]{4}$", sender.pattern());
        assertEquals(RuleCondition.Field.TEXT, text.field());
        assertEquals(RuleCondition.Operator.NOT_REGEX, text.operator());
        assertEquals("(?i)(广告|推广)", text.pattern());

        assertTrue(RuleCondition.regexIssue("[").contains("正则语法无效"));
        try {
            RuleCondition.senderNotRegex("[");
            fail("Invalid regex must not be representable");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("正则语法无效"));
            assertFalse(exception.getMessage().contains("Unclosed character class"));
        }
    }

    @Test
    public void completeDslRoundTripsEverySupportedConditionCombination() {
        RuleAndGroup firstGroup =
                RuleAndGroup.of(
                        RuleBranch.of(
                                RuleCondition.appIn(List.of("demo.app.chat")),
                                RuleCondition.senderRegex("acme-.*")),
                        RuleBranch.of(
                                RuleCondition.appNotIn(List.of("demo.app.spam")),
                                RuleCondition.senderNotRegex("spam")));
        RuleAndGroup secondGroup =
                RuleAndGroup.of(
                        RuleBranch.of(
                                RuleCondition.textRegex("OTP-[0-9]+"),
                                RuleCondition.isIsland(true)),
                        RuleBranch.of(
                                RuleCondition.textNotRegex("广告"),
                                RuleCondition.isVerifyCode(false)));
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromParsed(
                        "demo-rule-full",
                        "active",
                        42L,
                        List.of(firstGroup, secondGroup));

        String dsl = StructuredRuleDsl.serialize(draft);

        assertTrue(dsl.contains("\"field\":\"app\",\"op\":\"in\""));
        assertTrue(dsl.contains("\"field\":\"app\",\"op\":\"not_in\""));
        assertTrue(dsl.contains("\"field\":\"sender\",\"op\":\"regex\""));
        assertTrue(dsl.contains("\"field\":\"sender\",\"op\":\"not_regex\""));
        assertTrue(dsl.contains("\"field\":\"text\",\"op\":\"regex\""));
        assertTrue(dsl.contains("\"field\":\"text\",\"op\":\"not_regex\""));
        assertTrue(dsl.contains("\"field\":\"isIsland\",\"op\":\"is\""));
        assertTrue(dsl.contains("\"field\":\"isVerifyCode\",\"op\":\"is\""));

        NotificationRule saved =
                new NotificationRule(
                        "demo-rule-full",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "active",
                        draft.summary(),
                        dsl,
                        42L);
        StructuredRuleDraft reparsed = StructuredRuleDsl.parse(saved);

        assertEquals(dsl, StructuredRuleDsl.serialize(reparsed));
        assertEquals(
                "acme-.*",
                reparsed.groups().get(0).branches().get(0).conditions().get(1).pattern());
        assertEquals(
                RuleCondition.Operator.NOT_REGEX,
                reparsed.groups().get(0).branches().get(1).conditions().get(1).operator());
        assertEquals(
                "广告",
                reparsed.groups().get(1).branches().get(1).conditions().get(0).pattern());
    }

    @Test
    public void complexStructuredConditionsGenerateAnAccurateSummary() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromParsed(
                        "",
                        "active",
                        42L,
                        List.of(
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.appIn(
                                                        List.of("demo.app.chat")),
                                                RuleCondition.senderRegex("acme-.*")),
                                        RuleBranch.of(
                                                RuleCondition.appNotIn(
                                                        List.of("demo.app.spam")),
                                                RuleCondition.senderNotRegex("spam"))),
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.textRegex("OTP-[0-9]+"),
                                                RuleCondition.isIsland(true)),
                                        RuleBranch.of(
                                                RuleCondition.textNotRegex("广告"),
                                                RuleCondition.isVerifyCode(false)))));

        assertEquals(
                "（应用包括 demo.app.chat 且 发送者匹配正则 /acme-.*/ "
                        + "或 应用不包括 demo.app.spam 且 发送者不匹配正则 /spam/） 且 "
                        + "（文本匹配正则 /OTP-[0-9]+/ 且 焦点通知 "
                        + "或 文本不匹配正则 /广告/ 且 非短信验证码）",
                draft.summary());
    }

    @Test
    public void parsingInvalidStoredRegexFailsWithSafeText() {
        NotificationRule invalid =
                new NotificationRule(
                        "demo-rule-invalid-regex",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "active",
                        "演示规则",
                        "{\"andGroups\":[{\"branches\":[["
                                + "{\"field\":\"sender\",\"op\":\"regex\",\"value\":\"[\"}"
                                + "]]}],\"ruleText\":\"演示规则\"}",
                        42L);

        try {
            StructuredRuleDsl.parse(invalid);
            fail("Invalid stored regex must not enter the structured editor");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("正则"));
            assertFalse(exception.getMessage().contains("Unclosed character class"));
        }
    }

    @Test
    public void advancedRegexConditionsSupportAddRemoveReorderAndEdit() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 100L);
        RuleAndGroup group = draft.groups().get(0);
        RuleBranch branch =
                group.branches()
                        .get(0)
                        .addCondition(RuleCondition.senderRegex("^acme-"))
                        .addCondition(RuleCondition.textNotRegex("广告"));
        branch =
                branch.replaceCondition(
                        1, branch.conditions().get(1).withPattern("^acme-.*"));
        branch =
                branch.replaceCondition(
                        2, branch.conditions().get(2).withPattern("推广"));
        branch =
                branch.replaceCondition(
                        2,
                        branch.conditions()
                                .get(2)
                                .withOperator(RuleCondition.Operator.REGEX));
        branch = branch.moveCondition(2, 0);
        draft = draft.replaceGroup(0, group.replaceBranch(0, branch));

        assertEquals(RuleCondition.Field.TEXT, draft.groups().get(0)
                .branches().get(0).conditions().get(0).field());
        assertEquals(
                RuleCondition.Operator.REGEX,
                draft.groups().get(0).branches().get(0).conditions().get(0).operator());
        assertEquals(
                "推广",
                draft.groups().get(0).branches().get(0).conditions().get(0).pattern());
        assertEquals(
                RuleCondition.Field.SENDER,
                draft.groups().get(0).branches().get(0).conditions().get(2).field());

        branch = draft.groups().get(0).branches().get(0).removeCondition(2);
        draft = draft.replaceGroup(0, draft.groups().get(0).replaceBranch(0, branch));

        assertEquals(2, draft.groups().get(0).branches().get(0).conditions().size());
        assertFalse(
                "Removed sender condition must not remain in the generated summary",
                draft.summary().contains("发送者"));
        assertTrue(draft.summary().contains("文本匹配正则 /推广/"));
    }
}
