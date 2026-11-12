package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.Objects;

public final class RuleWriteRecoveryController {
    private final SafeNotificationRuleWriter writer;

    private RuleRecoveryModel.State state = RuleRecoveryModel.State.IDLE;
    private SafeNotificationRuleWriter.PreparedRuleWrite pendingWrite;
    private RuleStoreSnapshot failedSnapshot;
    private String failureReason = "";
    private int controlledRetryCount;
    private boolean debugModeEnabled;
    private boolean compatibilityUnverified;

    public RuleWriteRecoveryController(
            RuleStoreGateway gateway, LastKnownGoodStore lkgStore) {
        writer =
                new SafeNotificationRuleWriter(
                        Objects.requireNonNull(gateway, "gateway"),
                        Objects.requireNonNull(lkgStore, "lkgStore"));
    }

    public synchronized ServiceResult<RuleCreationResult> saveStructuredRule(
            ConfirmedTargetSelection selection, StructuredRuleDraft draft) {
        if (hasPendingRecovery()) {
            return pendingRecoveryFailure();
        }
        return recordAttempt(writer.attemptStructuredRuleRecovery(selection, draft));
    }

    public synchronized ServiceResult<RuleCreationResult> createAllNotificationsRule(
            ConfirmedTargetSelection selection) {
        if (hasPendingRecovery()) {
            return pendingRecoveryFailure();
        }
        return recordAttempt(writer.attemptAllNotificationsRuleRecovery(selection));
    }

    public synchronized ServiceResult<RuleCreationResult> retry() {
        if (state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
            return ServiceResult.failure("持久只读故障态仅允许重新检测或再次尝试回滚。");
        }
        if (pendingWrite == null) {
            return ServiceResult.failure("当前没有可受控重试的写入。");
        }
        if (!debugModeEnabled && controlledRetryCount >= 1) {
            return ServiceResult.failure("默认受控重试已使用；调试模式关闭时不能再次重试。");
        }
        SafeNotificationRuleWriter.RecoveryInspection inspection =
                writer.inspectPreparedWriteRecovery(
                        pendingWrite, failedSnapshot, debugModeEnabled);
        if (!inspection.canRetry()) {
            return ServiceResult.failure(inspection.message());
        }

        SafeNotificationRuleWriter.RecoveryRetryAttempt retry =
                writer.retryPreparedWriteRecovery(
                        pendingWrite, failedSnapshot, debugModeEnabled);
        if (retry.success()) {
            controlledRetryCount++;
            state = RuleRecoveryModel.State.SUCCESS;
            pendingWrite = null;
            failedSnapshot = null;
            debugModeEnabled = false;
            return ServiceResult.success(retry.result());
        }
        if (!retry.writeAttempted()) {
            return ServiceResult.failure(retry.message());
        }

        controlledRetryCount++;
        state =
                retry.readOnlyFault()
                        ? RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT
                        : RuleRecoveryModel.State.ROLLED_BACK;
        debugModeEnabled = false;
        return ServiceResult.failure(retry.message());
    }

    public synchronized ServiceResult<RuleRecoveryModel> rollback() {
        if (state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
            return retryRollback();
        }
        if (pendingWrite == null) {
            return ServiceResult.failure("当前没有可回滚的写入。");
        }
        SafeNotificationRuleWriter.RecoveryRollbackAttempt rollback =
                writer.rollbackPreparedWriteRecovery(pendingWrite, failedSnapshot);
        if (!rollback.verified()) {
            state =
                    writer.isReadOnlyFault()
                            ? RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT
                            : RuleRecoveryModel.State.AWAITING_DECISION;
            debugModeEnabled = false;
            return ServiceResult.failure(rollback.message());
        }

        state = RuleRecoveryModel.State.ROLLED_BACK;
        pendingWrite = null;
        failedSnapshot = null;
        debugModeEnabled = false;
        return ServiceResult.success(model());
    }

    public synchronized ServiceResult<RuleRecoveryModel> redetect() {
        if (state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT
                && pendingWrite != null) {
            SafeNotificationRuleWriter.RecoveryInspection inspection =
                    writer.inspectPreparedWriteRecovery(
                            pendingWrite, failedSnapshot, false);
            if (inspection.canRollback()) {
                state = RuleRecoveryModel.State.AWAITING_DECISION;
            }
        }
        return ServiceResult.success(model());
    }

    public synchronized ServiceResult<RuleRecoveryModel> retryRollback() {
        if (pendingWrite == null) {
            return ServiceResult.failure("当前没有可在持久故障态重试的回滚。");
        }
        SafeNotificationRuleWriter.RecoveryRollbackAttempt rollback =
                writer.rollbackPreparedWriteRecovery(pendingWrite, failedSnapshot);
        if (!rollback.verified()) {
            state = RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
            debugModeEnabled = false;
            return ServiceResult.failure(rollback.message());
        }
        state = RuleRecoveryModel.State.ROLLED_BACK;
        pendingWrite = null;
        failedSnapshot = null;
        debugModeEnabled = false;
        return ServiceResult.success(model());
    }

    public synchronized RuleRecoveryModel model() {
        SafeNotificationRuleWriter.RecoveryInspection inspection = null;
        if (pendingWrite != null
                && state == RuleRecoveryModel.State.AWAITING_DECISION) {
            inspection =
                    writer.inspectPreparedWriteRecovery(
                            pendingWrite, failedSnapshot, debugModeEnabled);
        }
        boolean canRetry =
                inspection != null
                        && inspection.canRetry()
                        && (debugModeEnabled || controlledRetryCount < 1);
        boolean canRollback =
                pendingWrite != null
                        && state == RuleRecoveryModel.State.AWAITING_DECISION;
        boolean canRedetect =
                state == RuleRecoveryModel.State.AWAITING_DECISION
                        || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
        boolean canRetryRollback =
                pendingWrite != null
                        && state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
        return new RuleRecoveryModel(
                state,
                failureReason,
                modelMessage(inspection),
                canRetry,
                canRollback,
                canRedetect,
                canRetryRollback,
                debugModeEnabled,
                compatibilityUnverified);
    }

    public synchronized void setDebugMode(boolean enabled) {
        debugModeEnabled = enabled;
    }

    public synchronized void onPageExit() {
        debugModeEnabled = false;
    }

    public synchronized void onProcessRestart() {
        debugModeEnabled = false;
    }

    private ServiceResult<RuleCreationResult> recordAttempt(
            SafeNotificationRuleWriter.RecoverableWriteAttempt attempt) {
        controlledRetryCount = 0;
        compatibilityUnverified = false;
        if (attempt.result().isSuccess()) {
            state = RuleRecoveryModel.State.SUCCESS;
            failureReason = "";
            pendingWrite = null;
            failedSnapshot = null;
            debugModeEnabled = false;
            compatibilityUnverified =
                    attempt.result().value().orElseThrow().compatibilityUnverified();
            return attempt.result();
        }
        if (!attempt.recoverable()) {
            state = RuleRecoveryModel.State.IDLE;
            failureReason = attempt.failureReason();
            pendingWrite = null;
            failedSnapshot = null;
            return attempt.result();
        }

        state = RuleRecoveryModel.State.AWAITING_DECISION;
        pendingWrite = attempt.plan();
        failedSnapshot = attempt.failedSnapshot();
        failureReason = attempt.failureReason();
        compatibilityUnverified = pendingWrite.compatibilityUnverified();
        return attempt.result();
    }

    private boolean hasPendingRecovery() {
        return pendingWrite != null
                && (state == RuleRecoveryModel.State.AWAITING_DECISION
                        || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT);
    }

    private ServiceResult<RuleCreationResult> pendingRecoveryFailure() {
        return ServiceResult.failure(
                "当前仍有待恢复的失败写入；请先回滚或完成受控重试。");
    }

    private String modelMessage(
            SafeNotificationRuleWriter.RecoveryInspection inspection) {
        StringBuilder message = new StringBuilder();
        switch (state) {
            case IDLE:
                message.append("当前没有待恢复的写入。");
                break;
            case AWAITING_DECISION:
                message.append(failureReason)
                        .append("\n推荐立即回滚。默认受控重试最多一次，重试前必须重新读取并验证当前状态。");
                if (inspection != null && !inspection.canRetry()) {
                    message.append("\n当前状态不安全，受控重试已禁止：")
                            .append(inspection.message());
                }
                break;
            case SUCCESS:
                message.append("写入已通过完整回读校验。");
                break;
            case ROLLED_BACK:
                message.append(failureReason)
                        .append("\n已回滚并验证原状态；不会自动重放原写入。");
                break;
            case PERSISTENT_READ_ONLY_FAULT:
                message.append(failureReason)
                        .append("\n回滚结果无法验证，当前处于持久只读故障态。")
                        .append("仅可重新检测或再次尝试回滚。");
                break;
            default:
                throw new IllegalStateException("Unknown recovery state " + state);
        }
        if (compatibilityUnverified) {
            message.append('\n')
                    .append("兼容性未经完整验收：运行时能力探测已通过，但当前 VoiceAssist 版本未完成完整验收。");
        }
        return message.toString();
    }
}
