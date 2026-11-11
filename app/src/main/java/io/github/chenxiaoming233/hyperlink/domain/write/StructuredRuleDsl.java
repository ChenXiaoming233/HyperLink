package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleAndGroup;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleBranch;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StructuredRuleDsl {
    private StructuredRuleDsl() {}

    public static String serialize(StructuredRuleDraft draft) {
        if (!draft.canSave()) {
            throw new IllegalArgumentException("Rule draft has no filter groups");
        }
        StringBuilder json = new StringBuilder("{\"andGroups\":[");
        for (int groupIndex = 0; groupIndex < draft.groups().size(); groupIndex++) {
            if (groupIndex > 0) {
                json.append(',');
            }
            RuleAndGroup group = draft.groups().get(groupIndex);
            json.append("{\"branches\":[");
            for (int branchIndex = 0; branchIndex < group.branches().size(); branchIndex++) {
                if (branchIndex > 0) {
                    json.append(',');
                }
                appendBranch(json, group.branches().get(branchIndex));
            }
            json.append("]}");
        }
        json.append("],\"ruleText\":")
                .append(RuleSerialization.quote(draft.summary()))
                .append('}');
        return json.toString();
    }

    public static StructuredRuleDraft parse(NotificationRule rule) {
        if (!RuleDslValidator.isValid(rule.dslJson())) {
            throw new IllegalArgumentException("Rule DSL cannot be edited as structured conditions");
        }
        Map<String, Object> root = castMap(RuleDslValidator.parse(rule.dslJson()));
        List<RuleAndGroup> groups = new ArrayList<>();
        if (root.containsKey("andGroups")) {
            for (Object groupValue : asList(root.get("andGroups"))) {
                Map<String, Object> group = castMap(groupValue);
                List<RuleBranch> branches = new ArrayList<>();
                for (Object branchValue : asList(group.get("branches"))) {
                    List<RuleCondition> conditions = new ArrayList<>();
                    for (Object conditionValue : asList(branchValue)) {
                        conditions.add(parseCondition(castMap(conditionValue)));
                    }
                    branches.add(RuleBranch.of(conditions));
                }
                groups.add(RuleAndGroup.of(branches));
            }
        }
        return StructuredRuleDraft.fromParsed(
                rule.ruleId(), rule.status(), rule.createdAt(), groups);
    }

    private static void appendBranch(StringBuilder json, RuleBranch branch) {
        json.append('[');
        for (int conditionIndex = 0; conditionIndex < branch.conditions().size(); conditionIndex++) {
            if (conditionIndex > 0) {
                json.append(',');
            }
            appendCondition(json, branch.conditions().get(conditionIndex));
        }
        json.append(']');
    }

    private static void appendCondition(StringBuilder json, RuleCondition condition) {
        json.append("{\"field\":")
                .append(RuleSerialization.quote(fieldName(condition.field())))
                .append(",\"op\":")
                .append(RuleSerialization.quote(operatorName(condition.operator())))
                .append(",\"value\":");
        if (condition.field() == RuleCondition.Field.APP) {
            json.append(RuleSerialization.quote(appListJson(condition.appPackages())));
        } else {
            json.append(RuleSerialization.quote(condition.booleanValue() ? "true" : "false"));
        }
        json.append('}');
    }

    private static RuleCondition parseCondition(Map<String, Object> condition) {
        String field = (String) condition.get("field");
        String operator = (String) condition.get("op");
        String value = (String) condition.get("value");
        if ("app".equals(field)) {
            List<String> packages = new ArrayList<>();
            for (Object packageName : asList(RuleDslValidator.parse(value))) {
                packages.add((String) packageName);
            }
            return "not_in".equals(operator)
                    ? RuleCondition.appNotIn(packages)
                    : RuleCondition.appIn(packages);
        }
        boolean booleanValue = Boolean.parseBoolean(value);
        return "isIsland".equals(field)
                ? RuleCondition.isIsland(booleanValue)
                : RuleCondition.isVerifyCode(booleanValue);
    }

    private static String appListJson(List<String> packages) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < packages.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(RuleSerialization.quote(packages.get(index)));
        }
        return value.append(']').toString();
    }

    private static String fieldName(RuleCondition.Field field) {
        switch (field) {
            case APP:
                return "app";
            case ISLAND:
                return "isIsland";
            case VERIFY_CODE:
                return "isVerifyCode";
            default:
                throw new IllegalStateException("Unhandled condition field " + field);
        }
    }

    private static String operatorName(RuleCondition.Operator operator) {
        switch (operator) {
            case IN:
                return "in";
            case NOT_IN:
                return "not_in";
            case IS:
                return "is";
            default:
                throw new IllegalStateException("Unhandled condition operator " + operator);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
