package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleAndGroup;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleBranch;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class StructuredRuleDslSemanticsTest {
    @Test
    public void completeConditionCombinationsSerializeAndDeserializeExactValues() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromParsed(
                        "demo-rule-semantics",
                        "active",
                        42L,
                        List.of(
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.appIn(
                                                        List.of(
                                                                "demo.app.chat",
                                                                "demo.app.otp")),
                                                RuleCondition.isIsland(true),
                                                RuleCondition.isVerifyCode(false)),
                                        RuleBranch.of(
                                                RuleCondition.appNotIn(
                                                        List.of("demo.app.spam")),
                                                RuleCondition.isIsland(false),
                                                RuleCondition.isVerifyCode(true))),
                                RuleAndGroup.of(
                                        RuleBranch.of(
                                                RuleCondition.senderRegex("^acme-.*$"),
                                                RuleCondition.textNotRegex("(?i)广告|推广")),
                                        RuleBranch.of(
                                                RuleCondition.senderNotRegex("^no-reply@"),
                                                RuleCondition.textRegex("OTP-[0-9]{6}")))));

        String dsl = StructuredRuleDsl.serialize(draft);
        Map<String, Object> root = map(RuleDslValidator.parse(dsl));

        assertRawCondition(root, 0, 0, 0, "app", "in",
                "[\"demo.app.chat\",\"demo.app.otp\"]");
        assertRawCondition(root, 0, 0, 1, "isIsland", "is", "true");
        assertRawCondition(root, 0, 0, 2, "isVerifyCode", "is", "false");
        assertRawCondition(root, 0, 1, 0, "app", "not_in",
                "[\"demo.app.spam\"]");
        assertRawCondition(root, 0, 1, 1, "isIsland", "is", "false");
        assertRawCondition(root, 0, 1, 2, "isVerifyCode", "is", "true");
        assertRawCondition(root, 1, 0, 0, "sender", "regex", "^acme-.*$");
        assertRawCondition(root, 1, 0, 1, "text", "not_regex", "(?i)广告|推广");
        assertRawCondition(root, 1, 1, 0, "sender", "not_regex", "^no-reply@");
        assertRawCondition(root, 1, 1, 1, "text", "regex", "OTP-[0-9]{6}");

        NotificationRule saved =
                new NotificationRule(
                        "demo-rule-semantics",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "active",
                        draft.summary(),
                        dsl,
                        42L);
        StructuredRuleDraft parsed = StructuredRuleDsl.parse(saved);

        assertCondition(parsed, 0, 0, 0, RuleCondition.Field.APP,
                RuleCondition.Operator.IN, false,
                List.of("demo.app.chat", "demo.app.otp"), "");
        assertCondition(parsed, 0, 0, 1, RuleCondition.Field.ISLAND,
                RuleCondition.Operator.IS, true, List.of(), "");
        assertCondition(parsed, 0, 0, 2, RuleCondition.Field.VERIFY_CODE,
                RuleCondition.Operator.IS, false, List.of(), "");
        assertCondition(parsed, 0, 1, 0, RuleCondition.Field.APP,
                RuleCondition.Operator.NOT_IN, false, List.of("demo.app.spam"), "");
        assertCondition(parsed, 0, 1, 1, RuleCondition.Field.ISLAND,
                RuleCondition.Operator.IS, false, List.of(), "");
        assertCondition(parsed, 0, 1, 2, RuleCondition.Field.VERIFY_CODE,
                RuleCondition.Operator.IS, true, List.of(), "");
        assertCondition(parsed, 1, 0, 0, RuleCondition.Field.SENDER,
                RuleCondition.Operator.REGEX, false, List.of(), "^acme-.*$");
        assertCondition(parsed, 1, 0, 1, RuleCondition.Field.TEXT,
                RuleCondition.Operator.NOT_REGEX, false, List.of(), "(?i)广告|推广");
        assertCondition(parsed, 1, 1, 0, RuleCondition.Field.SENDER,
                RuleCondition.Operator.NOT_REGEX, false, List.of(), "^no-reply@");
        assertCondition(parsed, 1, 1, 1, RuleCondition.Field.TEXT,
                RuleCondition.Operator.REGEX, false, List.of(), "OTP-[0-9]{6}");
    }

    private static void assertRawCondition(
            Map<String, Object> root,
            int groupIndex,
            int branchIndex,
            int conditionIndex,
            String field,
            String operator,
            String value) {
        Map<String, Object> condition =
                rawCondition(root, groupIndex, branchIndex, conditionIndex);
        assertEquals(field, condition.get("field"));
        assertEquals(operator, condition.get("op"));
        assertEquals(value, condition.get("value"));
    }

    private static void assertCondition(
            StructuredRuleDraft draft,
            int groupIndex,
            int branchIndex,
            int conditionIndex,
            RuleCondition.Field field,
            RuleCondition.Operator operator,
            boolean booleanValue,
            List<String> packages,
            String pattern) {
        RuleCondition condition =
                draft.groups()
                        .get(groupIndex)
                        .branches()
                        .get(branchIndex)
                        .conditions()
                        .get(conditionIndex);
        assertEquals(field, condition.field());
        assertEquals(operator, condition.operator());
        if (field == RuleCondition.Field.APP) {
            assertEquals(packages, condition.appPackages());
            assertFalse(condition.booleanValue());
            assertEquals("", condition.pattern());
        } else if (field == RuleCondition.Field.ISLAND
                || field == RuleCondition.Field.VERIFY_CODE) {
            assertEquals(booleanValue, condition.booleanValue());
            assertTrue(condition.appPackages().isEmpty());
            assertEquals("", condition.pattern());
        } else {
            assertFalse(condition.booleanValue());
            assertTrue(condition.appPackages().isEmpty());
            assertEquals(pattern, condition.pattern());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawCondition(
            Map<String, Object> root,
            int groupIndex,
            int branchIndex,
            int conditionIndex) {
        List<Object> groups = (List<Object>) root.get("andGroups");
        Map<String, Object> group = map(groups.get(groupIndex));
        List<Object> branches = (List<Object>) group.get("branches");
        List<Object> branch = (List<Object>) branches.get(branchIndex);
        return map(branch.get(conditionIndex));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
