package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Objects;

public final class RuleRecoveryModel {
    public enum State {
        IDLE,
        AWAITING_DECISION,
        SUCCESS,
        ROLLED_BACK,
        PERSISTENT_READ_ONLY_FAULT
    }

    private static final String COMPATIBILITY_WARNING =
            "兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。";
    private static final String DEBUG_WARNING =
            "高风险调试模式：仅取消重试次数与重试前状态验证，"
                    + "不会绕过能力探测、认证、未知字段/DSL 禁写、LKG、写入后校验或回滚保护。";

    private final State state;
    private final String failureReason;
    private final String message;
    private final boolean canRetry;
    private final boolean canRollback;
    private final boolean canRedetect;
    private final boolean canRetryRollback;
    private final boolean debugModeEnabled;
    private final boolean compatibilityUnverified;

    RuleRecoveryModel(
            State state,
            String failureReason,
            String message,
            boolean canRetry,
            boolean canRollback,
            boolean canRedetect,
            boolean canRetryRollback,
            boolean debugModeEnabled,
            boolean compatibilityUnverified) {
        this.state = Objects.requireNonNull(state, "state");
        this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
        this.message = Objects.requireNonNull(message, "message");
        this.canRetry = canRetry;
        this.canRollback = canRollback;
        this.canRedetect = canRedetect;
        this.canRetryRollback = canRetryRollback;
        this.debugModeEnabled = debugModeEnabled;
        this.compatibilityUnverified = compatibilityUnverified;
    }

    public State state() {
        return state;
    }

    public String failureReason() {
        return failureReason;
    }

    public String message() {
        return message;
    }

    public boolean canRetry() {
        return canRetry;
    }

    public boolean canRollback() {
        return canRollback;
    }

    public boolean canRedetect() {
        return canRedetect;
    }

    public boolean canRetryRollback() {
        return canRetryRollback;
    }

    public boolean debugModeEnabled() {
        return debugModeEnabled;
    }

    public boolean compatibilityUnverified() {
        return compatibilityUnverified;
    }

    public String compatibilityWarning() {
        return compatibilityUnverified ? COMPATIBILITY_WARNING : "";
    }

    public String debugWarning() {
        return DEBUG_WARNING;
    }
}
