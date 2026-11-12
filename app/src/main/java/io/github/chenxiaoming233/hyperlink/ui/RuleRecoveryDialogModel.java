package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.write.RuleRecoveryModel;
import java.util.Objects;

public final class RuleRecoveryDialogModel {
    public enum Action {
        ROLLBACK,
        RETRY,
        DEFER
    }

    private final String title;
    private final String message;
    private final Action recommendedAction;
    private final String rollbackLabel;
    private final String retryLabel;
    private final String deferLabel;
    private final boolean retryEnabled;
    private final boolean rollbackEnabled;

    private RuleRecoveryDialogModel(
            String title,
            String message,
            Action recommendedAction,
            boolean retryEnabled,
            boolean rollbackEnabled) {
        this.title = Objects.requireNonNull(title, "title");
        this.message = Objects.requireNonNull(message, "message");
        this.recommendedAction = Objects.requireNonNull(recommendedAction, "recommendedAction");
        this.rollbackLabel = "立即回滚（推荐）";
        this.retryLabel = "受控重试一次";
        this.deferLabel = "暂不操作";
        this.retryEnabled = retryEnabled;
        this.rollbackEnabled = rollbackEnabled;
    }

    public static RuleRecoveryDialogModel from(RuleRecoveryModel recovery) {
        Objects.requireNonNull(recovery, "recovery");
        return new RuleRecoveryDialogModel(
                title(recovery.state()),
                recovery.message(),
                recommendedAction(recovery.state()),
                recovery.state() == RuleRecoveryModel.State.AWAITING_DECISION
                        && recovery.canRetry(),
                recovery.state() == RuleRecoveryModel.State.AWAITING_DECISION
                        && recovery.canRollback());
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    public Action recommendedAction() {
        return recommendedAction;
    }

    public String rollbackLabel() {
        return rollbackLabel;
    }

    public String retryLabel() {
        return retryLabel;
    }

    public String deferLabel() {
        return deferLabel;
    }

    public boolean retryEnabled() {
        return retryEnabled;
    }

    public boolean rollbackEnabled() {
        return rollbackEnabled;
    }

    private static String title(RuleRecoveryModel.State state) {
        switch (state) {
            case AWAITING_DECISION:
                return "写入失败，需要恢复";
            case CURRENT_STATE_UNSAFE:
                return "当前状态不安全";
            case PERSISTENT_READ_ONLY_FAULT:
                return "回滚无法验证，写入已停用";
            case ROLLED_BACK:
                return "写入已回滚";
            case SUCCESS:
                return "写入已完成";
            case IDLE:
            default:
                return "写入恢复";
        }
    }

    private static Action recommendedAction(RuleRecoveryModel.State state) {
        if (state == RuleRecoveryModel.State.AWAITING_DECISION
                || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
            return Action.ROLLBACK;
        }
        return Action.DEFER;
    }
}
