package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.List;

public enum RulePreset {
    IM("IM 消息"),
    FOCUSED_NOTIFICATION("焦点通知"),
    VERIFICATION_CODE("短信验证码");

    private final String label;

    RulePreset(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    RuleBranch toBranch(List<String> imPackages) {
        switch (this) {
            case IM:
                if (imPackages == null || imPackages.isEmpty()) {
                    throw new IllegalArgumentException("IM 预设至少需要一个应用");
                }
                return RuleBranch.of(RuleCondition.appIn(imPackages));
            case FOCUSED_NOTIFICATION:
                return RuleBranch.of(RuleCondition.isIsland(true));
            case VERIFICATION_CODE:
                return RuleBranch.of(RuleCondition.isVerifyCode(true));
            default:
                throw new IllegalStateException("Unhandled rule preset " + this);
        }
    }
}
