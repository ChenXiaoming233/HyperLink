package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import java.util.Objects;

public final class RuleWriteRecoveryController {
    private final SafeNotificationRuleWriter writer;

    private RuleRecoveryModel.State state = RuleRecoveryModel.State.IDLE;
    private SafeNotificationRuleWriter.PreparedRuleWrite pendingWrite;
    private SafeNotificationRuleWriter.PreparedRuleMutation pendingMutation;
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

    public RuleWriteRecoveryController(SafeNotificationRuleWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
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

    public synchronized ServiceResult<RuleMutationResult> changeRuleStatus(
            String ruleId,
            String expectedTargetDeviceId,
            String expectedStatus,
            String nextStatus,
            boolean expectedTargetAuthorized) {
        if (hasPendingRecovery()) {
            return ServiceResult.failure(pendingRecoveryFailure().userMessage());
        }
        return recordMutationAttempt(
                writer.attemptChangeRuleStatusRecovery(
                        ruleId,
                        expectedTargetDeviceId,
                        expectedStatus,
                        nextStatus,
                        expectedTargetAuthorized));
    }

    public synchronized ServiceResult<RuleMutationResult> deleteRule(
            String ruleId,
            String expectedTargetDeviceId,
            boolean expectedTargetAuthorized) {
        if (hasPendingRecovery()) {
            return ServiceResult.failure(pendingRecoveryFailure().userMessage());
        }
        return recordMutationAttempt(
                writer.attemptDeleteRuleRecovery(
                        ruleId, expectedTargetDeviceId, expectedTargetAuthorized));
    }

    public synchronized boolean hasPendingRecovery() {
        return (pendingWrite != null || pendingMutation != null)
                && (state == RuleRecoveryModel.State.AWAITING_DECISION
                        || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT);
    }

    public synchronized boolean hasPendingWriteRecovery() {
        return pendingWrite != null
                && (state == RuleRecoveryModel.State.AWAITING_DECISION
                        || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT);
    }

    public synchronized boolean hasPendingMutationRecovery() {
        return pendingMutation != null
                && (state == RuleRecoveryModel.State.AWAITING_DECISION
                        || state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT);
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
            compatibilityUnverified =
                    retry.result().compatibilityUnverified();
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

    public synchronized ServiceResult<RuleMutationResult> retryMutation() {
        if (state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
            return ServiceResult.failure("持久只读故障态仅允许重新检测或再次尝试回滚。");
        }
        if (pendingMutation == null) {
            return ServiceResult.failure("当前没有可受控重试的生命周期写入。");
        }
        if (!debugModeEnabled && controlledRetryCount >= 1) {
            return ServiceResult.failure("默认受控重试已使用；调试模式关闭时不能再次重试。");
        }
        SafeNotificationRuleWriter.RecoveryInspection inspection =
                writer.inspectPreparedMutationRecovery(
                        pendingMutation, failedSnapshot, debugModeEnabled);
        if (!inspection.canRetry()) {
            return ServiceResult.failure(inspection.message());
        }

        SafeNotificationRuleWriter.RecoveryMutationRetryAttempt retry =
                writer.retryPreparedMutationRecovery(
                        pendingMutation, failedSnapshot, debugModeEnabled);
        if (retry.success()) {
            controlledRetryCount++;
            state = RuleRecoveryModel.State.SUCCESS;
            compatibilityUnverified =
                    retry.result().compatibilityUnverified();
            pendingMutation = null;
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
        if (pendingWrite == null && pendingMutation == null) {
            return ServiceResult.failure("当前没有可回滚的写入。");
        }
        SafeNotificationRuleWriter.RecoveryRollbackAttempt rollback =
                pendingMutation == null
                        ? writer.rollbackPreparedWriteRecovery(
                                pendingWrite, failedSnapshot)
                        : writer.rollbackPreparedMutationRecovery(
                                pendingMutation, failedSnapshot);
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
        pendingMutation = null;
        failedSnapshot = null;
        debugModeEnabled = false;
        return ServiceResult.success(model());
    }

    public synchronized ServiceResult<RuleRecoveryModel> redetect() {
        escalateLocalFaultIfNeeded();
        if (state != RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
            return ServiceResult.failure(
                    "仅持久只读故障态允许重新检测。");
        }
        if (pendingWrite != null || pendingMutation != null) {
            if (pendingMutation == null) {
                writer.inspectPreparedWriteRecovery(
                        pendingWrite, failedSnapshot, false);
            } else {
                writer.inspectPreparedMutationRecovery(
                        pendingMutation, failedSnapshot, false);
            }
        }
        return ServiceResult.success(model());
    }

    public synchronized ServiceResult<RuleRecoveryModel> retryRollback() {
        if (pendingWrite == null && pendingMutation == null) {
            return ServiceResult.failure("当前没有可在持久故障态重试的回滚。");
        }
        SafeNotificationRuleWriter.RecoveryRollbackAttempt rollback =
                pendingMutation == null
                        ? writer.rollbackPreparedWriteRecovery(
                                pendingWrite, failedSnapshot)
                        : writer.rollbackPreparedMutationRecovery(
                                pendingMutation, failedSnapshot);
        if (!rollback.verified()) {
            state = RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
            debugModeEnabled = false;
            return ServiceResult.failure(rollback.message());
        }
        state = RuleRecoveryModel.State.ROLLED_BACK;
        pendingWrite = null;
        pendingMutation = null;
        failedSnapshot = null;
        debugModeEnabled = false;
        return ServiceResult.success(model());
    }

    public synchronized RuleRecoveryModel model() {
        escalateLocalFaultIfNeeded();
        SafeNotificationRuleWriter.RecoveryInspection inspection = null;
        if ((pendingWrite != null || pendingMutation != null)
                && state == RuleRecoveryModel.State.AWAITING_DECISION) {
            inspection =
                    pendingMutation == null
                            ? writer.inspectPreparedWriteRecovery(
                                    pendingWrite, failedSnapshot, debugModeEnabled)
                            : writer.inspectPreparedMutationRecovery(
                                    pendingMutation, failedSnapshot, debugModeEnabled);
        }
        boolean canRetry =
                inspection != null
                        && inspection.canRetry()
                        && (debugModeEnabled || controlledRetryCount < 1);
        boolean canRollback =
                (pendingWrite != null || pendingMutation != null)
                        && state == RuleRecoveryModel.State.AWAITING_DECISION
                        && (inspection == null || inspection.canRollback());
        boolean canRedetect =
                state == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
        boolean canRetryRollback =
                (pendingWrite != null || pendingMutation != null)
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

    public synchronized void enterReadOnlyFault(String message) {
        writer.enterReadOnlyFault(
                Objects.requireNonNull(message, "message"));
        escalateLocalFaultIfNeeded();
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
            pendingMutation = null;
            failedSnapshot = null;
            debugModeEnabled = false;
            compatibilityUnverified =
                    attempt.result().value().orElseThrow().compatibilityUnverified();
            return attempt.result();
        }
        if (!attempt.recoverable()) {
            state =
                    attempt.unsafe()
                            ? RuleRecoveryModel.State.CURRENT_STATE_UNSAFE
                            : RuleRecoveryModel.State.IDLE;
            compatibilityUnverified = attempt.compatibilityUnverified();
            failureReason = attempt.failureReason();
            pendingWrite = null;
            pendingMutation = null;
            failedSnapshot = null;
            return attempt.result();
        }

        state = RuleRecoveryModel.State.AWAITING_DECISION;
        pendingWrite = attempt.plan();
        pendingMutation = null;
        failedSnapshot = attempt.failedSnapshot();
        failureReason = attempt.failureReason();
        compatibilityUnverified = pendingWrite.compatibilityUnverified();
        return attempt.result();
    }

    private ServiceResult<RuleMutationResult> recordMutationAttempt(
            SafeNotificationRuleWriter.RecoverableMutationAttempt attempt) {
        controlledRetryCount = 0;
        compatibilityUnverified = false;
        if (attempt.result().isSuccess()) {
            state = RuleRecoveryModel.State.SUCCESS;
            failureReason = "";
            pendingWrite = null;
            pendingMutation = null;
            failedSnapshot = null;
            debugModeEnabled = false;
            compatibilityUnverified =
                    attempt.result().value().orElseThrow().compatibilityUnverified();
            return attempt.result();
        }
        if (!attempt.recoverable()) {
            state =
                    attempt.unsafe()
                            ? RuleRecoveryModel.State.CURRENT_STATE_UNSAFE
                            : RuleRecoveryModel.State.IDLE;
            compatibilityUnverified = attempt.compatibilityUnverified();
            failureReason = attempt.failureReason();
            pendingWrite = null;
            pendingMutation = null;
            failedSnapshot = null;
            return attempt.result();
        }

        state = RuleRecoveryModel.State.AWAITING_DECISION;
        pendingWrite = null;
        pendingMutation = attempt.plan();
        failedSnapshot = attempt.failedSnapshot();
        failureReason = attempt.failureReason();
        compatibilityUnverified = pendingMutation.compatibilityUnverified();
        return attempt.result();
    }

    private ServiceResult<RuleCreationResult> pendingRecoveryFailure() {
        return ServiceResult.failure(
                "当前仍有待恢复的失败写入；请先回滚或完成受控重试。");
    }

    private void escalateLocalFaultIfNeeded() {
        if ((pendingWrite != null || pendingMutation != null)
                && state == RuleRecoveryModel.State.AWAITING_DECISION
                && writer.isReadOnlyFault()) {
            state = RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT;
            debugModeEnabled = false;
        }
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
            case CURRENT_STATE_UNSAFE:
                message.append(failureReason)
                        .append("\n当前状态包含外部规则、目标或权限变化，"
                                + "不能作为重试安全基线；受控重试和自动回滚均已禁止。");
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
