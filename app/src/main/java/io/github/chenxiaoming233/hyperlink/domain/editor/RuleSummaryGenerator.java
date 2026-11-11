package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.List;

final class RuleSummaryGenerator {
    private RuleSummaryGenerator() {}

    static String generate(List<RuleAndGroup> groups) {
        if (groups.isEmpty()) {
            return "尚未添加筛选条件";
        }
        StringBuilder summary = new StringBuilder();
        for (RuleAndGroup group : groups) {
            if (summary.length() > 0) {
                summary.append(" 且 ");
            }
            String groupText = groupText(group);
            if (groups.size() > 1) {
                summary.append('（').append(groupText).append('）');
            } else {
                summary.append(groupText);
            }
        }
        return summary.toString();
    }

    private static String groupText(RuleAndGroup group) {
        StringBuilder text = new StringBuilder();
        for (RuleBranch branch : group.branches()) {
            if (text.length() > 0) {
                text.append(" 或 ");
            }
            text.append(branchText(branch));
        }
        return text.toString();
    }

    private static String branchText(RuleBranch branch) {
        StringBuilder text = new StringBuilder();
        for (RuleCondition condition : branch.conditions()) {
            if (text.length() > 0) {
                text.append(" 且 ");
            }
            text.append(conditionText(condition));
        }
        return text.toString();
    }

    private static String conditionText(RuleCondition condition) {
        switch (condition.field()) {
            case APP:
                return "应用"
                        + (condition.operator() == RuleCondition.Operator.IN ? "包括 " : "不包括 ")
                        + String.join("、", condition.appPackages());
            case ISLAND:
                return condition.booleanValue() ? "焦点通知" : "非焦点通知";
            case VERIFY_CODE:
                return condition.booleanValue() ? "短信验证码" : "非短信验证码";
            default:
                throw new IllegalStateException("Unhandled condition field " + condition.field());
        }
    }
}
