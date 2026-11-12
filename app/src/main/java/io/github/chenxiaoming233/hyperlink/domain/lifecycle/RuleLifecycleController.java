package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleMutationResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleRecoveryModel;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteRecoveryController;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class RuleLifecycleController {
    private final RuleStoreGateway ruleStore;
    private final RuleWriteRecoveryController recoveryController;
    private final TargetPermissionGateway permissionGateway;
    private final LongSupplier monotonicClock;
    private final long undoWindowMillis;

    private long nextSequence = 1L;
    private RuleUndoToken lastUndoToken;
    private DeleteConfirmation pendingDelete;
    private String transientBlockedError = "";
    private String transientConflictRuleId = "";
    private String transientConflictTargetDeviceId = "";
    private boolean transientConflictExpectedAuthorization;
    private String recoveryBlockedError = "";
    private List<String> mutationNotes = Collections.emptyList();
    private PendingLifecycleRecovery pendingRecovery;
    private RuleLifecycleModel model;

    public RuleLifecycleController(
            RuleStoreGateway ruleStore,
            SafeNotificationRuleWriter writer,
            TargetPermissionGateway permissionGateway,
            LongSupplier monotonicClock,
            long undoWindowMillis) {
        this(
                ruleStore,
                new RuleWriteRecoveryController(
                        Objects.requireNonNull(writer, "writer")),
                permissionGateway,
                monotonicClock,
                undoWindowMillis);
    }

    public RuleLifecycleController(
            RuleStoreGateway ruleStore,
            RuleWriteRecoveryController recoveryController,
            TargetPermissionGateway permissionGateway,
            LongSupplier monotonicClock,
            long undoWindowMillis) {
        this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore");
        this.recoveryController =
                Objects.requireNonNull(recoveryController, "recoveryController");
        this.permissionGateway =
                Objects.requireNonNull(permissionGateway, "permissionGateway");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        if (undoWindowMillis <= 0L) {
            throw new IllegalArgumentException("undoWindowMillis must be positive");
        }
        this.undoWindowMillis = undoWindowMillis;
    }

    public synchronized ServiceResult<RuleLifecycleModel> refresh() {
        return refreshLocked(true);
    }

    private ServiceResult<RuleLifecycleModel> refreshLocked(boolean clearTransientError) {
        ServiceResult<RuleStoreSnapshot> readResult;
        try {
            readResult = ruleStore.read();
        } catch (RuntimeException exception) {
            return ServiceResult.failure("无法读取规则生命周期状态，请稍后重试。");
        }
        if (!readResult.isSuccess()) {
            return ServiceResult.failure("无法读取规则生命周期状态，请稍后重试。");
        }
        RuleStoreSnapshot snapshot = readResult.value().orElseThrow();
        if (clearTransientError && conflictResolved(snapshot)) {
            clearTransientConflict();
        }
        model =
                RuleLifecycleModel.from(
                        snapshot,
                        lastUndoToken,
                        effectiveBlockedError(),
                        mutationNotes);
        return ServiceResult.success(model);
    }

    public synchronized RuleLifecycleModel model() {
        if (model == null) {
            ServiceResult<RuleLifecycleModel> result = refreshLocked(true);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.userMessage());
            }
        }
        return model;
    }

    public synchronized RuleRecoveryModel recoveryModel() {
        return recoveryController.model();
    }

    public synchronized boolean hasPendingRecovery() {
        return pendingRecovery != null && recoveryController.hasPendingRecovery();
    }

    public synchronized ServiceResult<RuleLifecycleModel> pause(String ruleId) {
        return changeStatus(ruleId, "active", "paused");
    }

    public synchronized ServiceResult<RuleLifecycleModel> resume(String ruleId) {
        return changeStatus(ruleId, "paused", "active");
    }

    public synchronized ServiceResult<RuleLifecycleModel> undo(RuleUndoToken token) {
        ServiceResult<RuleLifecycleModel> pendingFailure = rejectPendingRecovery();
        if (pendingFailure != null) {
            return pendingFailure;
        }
        if (token == null
                || lastUndoToken == null
                || token.sequence() != lastUndoToken.sequence()
                || !token.ruleId().equals(lastUndoToken.ruleId())) {
            return ServiceResult.failure("撤销操作已失效，未执行任何写入。");
        }
        if (monotonicClock.getAsLong() > token.expiresAtMillis()) {
            lastUndoToken = null;
            refreshLocked(true);
            return ServiceResult.failure("撤销时间窗口已过期，未执行任何写入。");
        }
        ServiceResult<RuleMutationResult> result =
                recoveryController.changeRuleStatus(
                        token.ruleId(),
                        token.targetDeviceId(),
                        token.nextStatus(),
                        token.previousStatus(),
                        token.targetAuthorized());
        if (!result.isSuccess()) {
            PendingLifecycleRecovery pending =
                    PendingLifecycleRecovery.undo(
                            token,
                            token.targetDeviceId(),
                            token.targetAuthorized());
            armRecovery(pending);
            return blocked(
                    result.userMessage(),
                    token.ruleId(),
                    token.targetDeviceId(),
                    token.targetAuthorized());
        }
        lastUndoToken = null;
        clearTransientConflict();
        mutationNotes = result.value().orElseThrow().notes();
        model =
                RuleLifecycleModel.from(
                        result.value().orElseThrow().snapshot(),
                        null,
                        "",
                        mutationNotes);
        return ServiceResult.success(model);
    }

    public synchronized ServiceResult<DeleteConfirmation> requestDelete(String ruleId) {
        ServiceResult<RuleLifecycleModel> pendingFailure = rejectPendingRecovery();
        if (pendingFailure != null) {
            return ServiceResult.failure(pendingFailure.userMessage());
        }
        if (ruleId == null || ruleId.trim().isEmpty()) {
            return ServiceResult.failure("未指定要删除的规则。");
        }
        ServiceResult<RuleLifecycleModel> current = ensureModel();
        if (!current.isSuccess()) {
            return ServiceResult.failure(current.userMessage());
        }
        RuleLifecycleModel.RuleItem rule =
                current.value().orElseThrow().rule(ruleId).orElse(null);
        if (rule == null) {
            return ServiceResult.failure("规则不存在或已被外部修改，请重新读取。");
        }
        if (!rule.canDelete()) {
            return ServiceResult.failure("当前规则不可删除，请先处理目标或故障状态。");
        }
        String prompt =
                "确认删除规则 "
                        + ruleId
                        + "？删除会同时撤销目标 "
                        + rule.targetDeviceName()
                        + "（"
                        + rule.targetDeviceId()
                        + "）的目标授权；这不会影响系统通知使用权。"
                        + "外部授权变化会阻止删除，不会自动重新授权。";
        DeleteConfirmation confirmation =
                new DeleteConfirmation(
                        nextSequence++,
                        ruleId,
                        rule.targetDeviceId(),
                        rule.targetDeviceName(),
                        rule.targetAuthorized(),
                        prompt);
        pendingDelete = confirmation;
        return ServiceResult.success(confirmation);
    }

    public synchronized ServiceResult<RuleLifecycleModel> confirmDelete(
            DeleteConfirmation confirmation, boolean confirmed) {
        ServiceResult<RuleLifecycleModel> pendingFailure = rejectPendingRecovery();
        if (pendingFailure != null) {
            return pendingFailure;
        }
        if (confirmation == null
                || pendingDelete == null
                || confirmation.sequence() != pendingDelete.sequence()
                || !confirmation.ruleId().equals(pendingDelete.ruleId())) {
            return ServiceResult.failure("删除确认已失效，未执行任何写入。");
        }
        pendingDelete = null;
        if (!confirmed) {
            return refreshLocked(true);
        }

        ServiceResult<RuleMutationResult> deleted =
                recoveryController.deleteRule(
                        confirmation.ruleId(),
                        confirmation.targetDeviceId(),
                        confirmation.expectedTargetAuthorized());
        if (!deleted.isSuccess()) {
            PendingLifecycleRecovery pending =
                    PendingLifecycleRecovery.delete(confirmation);
            armRecovery(pending);
            return blocked(
                    deleted.userMessage(),
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized());
        }
        RuleMutationResult mutation = deleted.value().orElseThrow();
        mutationNotes = mutation.notes();
        return completeDeleteAfterRuleRemoved(confirmation, mutation);
    }

    private ServiceResult<RuleLifecycleModel> changeStatus(
            String ruleId, String expectedStatus, String nextStatus) {
        ServiceResult<RuleLifecycleModel> pendingFailure = rejectPendingRecovery();
        if (pendingFailure != null) {
            return pendingFailure;
        }
        ServiceResult<RuleLifecycleModel> current = ensureModel();
        if (!current.isSuccess()) {
            return ServiceResult.failure(current.userMessage());
        }
        RuleLifecycleModel.RuleItem rule =
                current.value().orElseThrow().rule(ruleId).orElse(null);
        if (rule == null) {
            return ServiceResult.failure("规则不存在或已被外部修改，请重新读取。");
        }
        ServiceResult<RuleMutationResult> result =
                recoveryController.changeRuleStatus(
                        ruleId,
                        rule.targetDeviceId(),
                        expectedStatus,
                        nextStatus,
                        rule.targetAuthorized());
        if (!result.isSuccess()) {
            PendingLifecycleRecovery pending =
                    PendingLifecycleRecovery.status(
                            ruleId,
                            rule.targetDeviceId(),
                            rule.targetAuthorized(),
                            expectedStatus,
                            nextStatus);
            armRecovery(pending);
            return blocked(
                    result.userMessage(),
                    ruleId,
                    rule.targetDeviceId(),
                    rule.targetAuthorized());
        }
        RuleUndoToken undoToken =
                new RuleUndoToken(
                        nextSequence++,
                        ruleId,
                        expectedStatus,
                        nextStatus,
                        rule.targetDeviceId(),
                        rule.targetAuthorized(),
                        monotonicClock.getAsLong() + undoWindowMillis);
        lastUndoToken = undoToken;
        clearTransientConflict();
        mutationNotes = result.value().orElseThrow().notes();
        model =
                RuleLifecycleModel.from(
                        result.value().orElseThrow().snapshot(),
                        undoToken,
                        "",
                        mutationNotes);
        return ServiceResult.success(model);
    }

    public synchronized ServiceResult<RuleLifecycleModel> retryMutationRecovery() {
        if (pendingRecovery == null) {
            return ServiceResult.failure("当前没有可受控重试的生命周期写入。");
        }
        ServiceResult<RuleMutationResult> retried =
                recoveryController.retryMutation();
        if (!retried.isSuccess()) {
            return recoveryFailure(retried.userMessage());
        }

        PendingLifecycleRecovery completed = pendingRecovery;
        pendingRecovery = null;
        recoveryBlockedError = "";
        RuleMutationResult mutation = retried.value().orElseThrow();
        mutationNotes = mutation.notes();
        if (completed.kind == PendingKind.DELETE) {
            return completeDeleteAfterRuleRemoved(
                    completed.deleteConfirmation, mutation);
        }

        if (completed.kind == PendingKind.UNDO) {
            lastUndoToken = null;
        } else {
            lastUndoToken =
                    new RuleUndoToken(
                            nextSequence++,
                            completed.ruleId,
                            completed.expectedStatus,
                            completed.nextStatus,
                            completed.targetDeviceId,
                            completed.expectedTargetAuthorized,
                            monotonicClock.getAsLong() + undoWindowMillis);
        }
        clearTransientConflict();
        model =
                RuleLifecycleModel.from(
                        mutation.snapshot(),
                        lastUndoToken,
                        "",
                        mutationNotes);
        return ServiceResult.success(model);
    }

    public synchronized ServiceResult<RuleLifecycleModel> rollbackMutationRecovery() {
        if (pendingRecovery == null) {
            return ServiceResult.failure("当前没有可回滚的生命周期写入。");
        }
        ServiceResult<RuleRecoveryModel> rollback = recoveryController.rollback();
        if (!rollback.isSuccess()) {
            return recoveryFailure(rollback.userMessage());
        }
        pendingRecovery = null;
        recoveryBlockedError = "";
        mutationNotes = Collections.emptyList();
        clearTransientConflict();
        ServiceResult<RuleLifecycleModel> refreshed = refreshLocked(true);
        return refreshed.isSuccess()
                ? refreshed
                : ServiceResult.failure(refreshed.userMessage());
    }

    public synchronized ServiceResult<RuleLifecycleModel> retryRollbackMutationRecovery() {
        if (pendingRecovery == null) {
            return ServiceResult.failure("当前没有可再次尝试回滚的生命周期写入。");
        }
        ServiceResult<RuleRecoveryModel> rollback =
                recoveryController.retryRollback();
        if (!rollback.isSuccess()) {
            return recoveryFailure(rollback.userMessage());
        }
        pendingRecovery = null;
        recoveryBlockedError = "";
        mutationNotes = Collections.emptyList();
        clearTransientConflict();
        return refreshLocked(true);
    }

    public synchronized ServiceResult<RuleRecoveryModel> redetectRecovery() {
        ServiceResult<RuleRecoveryModel> result = recoveryController.redetect();
        if (pendingRecovery != null && !recoveryController.hasPendingRecovery()) {
            pendingRecovery = null;
        }
        refreshLocked(false);
        return result;
    }

    private ServiceResult<RuleLifecycleModel> completeDeleteAfterRuleRemoved(
            DeleteConfirmation confirmation, RuleMutationResult mutation) {
        if (!confirmation
                .targetDeviceId()
                .equals(mutation.affectedTargetDeviceId())) {
            String message =
                    "删除校验发现目标绑定不一致，未撤销任何目标授权；"
                            + "当前进入阻断故障状态。";
            pendingRecovery = null;
            lastUndoToken = null;
            return blocked(
                    enterReadOnlyFault(message),
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized());
        }

        ServiceResult<TargetPermissionReceipt> revoked;
        try {
            revoked = permissionGateway.revoke(confirmation.targetDeviceId());
        } catch (RuntimeException exception) {
            revoked = ServiceResult.failure("目标授权撤销异常。");
        }
        boolean revocationFailed = !revoked.isSuccess();
        if (!revocationFailed) {
            TargetPermissionReceipt receipt = revoked.value().orElseThrow();
            revocationFailed =
                    !receipt.targetDeviceId().equals(confirmation.targetDeviceId())
                            || receipt.authorized();
        }
        if (revocationFailed) {
            String message =
                    "规则已删除，但目标授权撤销失败；当前进入阻断故障状态，"
                            + "不会把删除或授权撤销显示为成功。";
            pendingRecovery = null;
            lastUndoToken = null;
            return blocked(
                    enterReadOnlyFault(message),
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized());
        }

        pendingRecovery = null;
        recoveryBlockedError = "";
        lastUndoToken = null;
        clearTransientConflict();
        return refreshLocked(true);
    }

    private boolean armRecovery(PendingLifecycleRecovery pending) {
        if (!recoveryController.hasPendingRecovery()) {
            pendingRecovery = null;
            recoveryBlockedError = "";
            return false;
        }
        pendingRecovery = pending;
        recoveryBlockedError = recoveryController.model().message();
        return true;
    }

    private ServiceResult<RuleLifecycleModel> recoveryFailure(String message) {
        RuleRecoveryModel recovery = recoveryController.model();
        if (recovery.state() == RuleRecoveryModel.State.ROLLED_BACK
                || !recoveryController.hasPendingRecovery()) {
            pendingRecovery = null;
            recoveryBlockedError = "";
        } else {
            recoveryBlockedError = recovery.message();
        }
        refreshLocked(false);
        return ServiceResult.failure(message);
    }

    private String enterReadOnlyFault(String message) {
        try {
            recoveryController.enterReadOnlyFault(message);
            return message;
        } catch (RuntimeException exception) {
            return message + " 无法持久化故障态，请停止后续写入。";
        }
    }

    private ServiceResult<RuleLifecycleModel> rejectPendingRecovery() {
        if (pendingRecovery == null) {
            return null;
        }
        return ServiceResult.failure(
                "当前仍有待恢复的生命周期写入；"
                        + "请先回滚、受控重试或重新检测，期间禁止新的写入。");
    }

    private String effectiveBlockedError() {
        return recoveryBlockedError.isEmpty()
                ? transientBlockedError
                : recoveryBlockedError;
    }

    private ServiceResult<RuleLifecycleModel> blocked(
            String message,
            String ruleId,
            String targetDeviceId,
            boolean expectedAuthorization) {
        transientBlockedError = Objects.requireNonNull(message, "message");
        transientConflictRuleId = Objects.requireNonNull(ruleId, "ruleId");
        transientConflictTargetDeviceId =
                Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        transientConflictExpectedAuthorization = expectedAuthorization;
        refreshLocked(false);
        return ServiceResult.failure(message);
    }

    private ServiceResult<RuleLifecycleModel> ensureModel() {
        return model == null ? refreshLocked(true) : ServiceResult.success(model);
    }

    private boolean conflictResolved(RuleStoreSnapshot snapshot) {
        if (!RuleLifecycleModel.writeBlockedReason(snapshot, "").isEmpty()) {
            return false;
        }
        if (transientConflictRuleId.isEmpty()) {
            return true;
        }
        NotificationRule rule = null;
        for (NotificationRule candidate : snapshot.rules()) {
            if (candidate.ruleId().equals(transientConflictRuleId)) {
                rule = candidate;
                break;
            }
        }
        if (rule == null
                || !rule.targetDeviceId().equals(transientConflictTargetDeviceId)) {
            return false;
        }
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            if (target.deviceId().equals(transientConflictTargetDeviceId)) {
                return target.targetAuthorized()
                        == transientConflictExpectedAuthorization;
            }
        }
        return false;
    }

    private void clearTransientConflict() {
        transientBlockedError = "";
        transientConflictRuleId = "";
        transientConflictTargetDeviceId = "";
    }

    private enum PendingKind {
        STATUS,
        UNDO,
        DELETE
    }

    private static final class PendingLifecycleRecovery {
        private final PendingKind kind;
        private final String ruleId;
        private final String targetDeviceId;
        private final boolean expectedTargetAuthorized;
        private final String expectedStatus;
        private final String nextStatus;
        private final DeleteConfirmation deleteConfirmation;

        private PendingLifecycleRecovery(
                PendingKind kind,
                String ruleId,
                String targetDeviceId,
                boolean expectedTargetAuthorized,
                String expectedStatus,
                String nextStatus,
                DeleteConfirmation deleteConfirmation) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
            this.targetDeviceId = Objects.requireNonNull(targetDeviceId, "targetDeviceId");
            this.expectedTargetAuthorized = expectedTargetAuthorized;
            this.expectedStatus = Objects.requireNonNull(expectedStatus, "expectedStatus");
            this.nextStatus = Objects.requireNonNull(nextStatus, "nextStatus");
            this.deleteConfirmation = deleteConfirmation;
        }

        private static PendingLifecycleRecovery status(
                String ruleId,
                String targetDeviceId,
                boolean expectedTargetAuthorized,
                String expectedStatus,
                String nextStatus) {
            return new PendingLifecycleRecovery(
                    PendingKind.STATUS,
                    ruleId,
                    targetDeviceId,
                    expectedTargetAuthorized,
                    expectedStatus,
                    nextStatus,
                    null);
        }

        private static PendingLifecycleRecovery undo(
                RuleUndoToken token,
                String targetDeviceId,
                boolean expectedTargetAuthorized) {
            return new PendingLifecycleRecovery(
                    PendingKind.UNDO,
                    token.ruleId(),
                    targetDeviceId,
                    expectedTargetAuthorized,
                    token.nextStatus(),
                    token.previousStatus(),
                    null);
        }

        private static PendingLifecycleRecovery delete(
                DeleteConfirmation confirmation) {
            return new PendingLifecycleRecovery(
                    PendingKind.DELETE,
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized(),
                    "",
                    "",
                    confirmation);
        }
    }
}
