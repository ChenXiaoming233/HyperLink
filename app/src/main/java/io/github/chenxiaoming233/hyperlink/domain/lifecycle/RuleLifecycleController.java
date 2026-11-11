package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleMutationResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class RuleLifecycleController {
    private final RuleStoreGateway ruleStore;
    private final SafeNotificationRuleWriter writer;
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
    private RuleLifecycleModel model;

    public RuleLifecycleController(
            RuleStoreGateway ruleStore,
            SafeNotificationRuleWriter writer,
            TargetPermissionGateway permissionGateway,
            LongSupplier monotonicClock,
            long undoWindowMillis) {
        this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore");
        this.writer = Objects.requireNonNull(writer, "writer");
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
                        transientBlockedError);
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

    public synchronized ServiceResult<RuleLifecycleModel> pause(String ruleId) {
        return changeStatus(ruleId, "active", "paused");
    }

    public synchronized ServiceResult<RuleLifecycleModel> resume(String ruleId) {
        return changeStatus(ruleId, "paused", "active");
    }

    public synchronized ServiceResult<RuleLifecycleModel> undo(RuleUndoToken token) {
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
        lastUndoToken = null;
        ServiceResult<RuleMutationResult> result =
                writer.changeRuleStatus(
                        token.ruleId(),
                        token.targetDeviceId(),
                        token.nextStatus(),
                        token.previousStatus(),
                        token.targetAuthorized());
        if (!result.isSuccess()) {
            return blocked(
                    result.userMessage(),
                    token.ruleId(),
                    token.targetDeviceId(),
                    token.targetAuthorized());
        }
        clearTransientConflict();
        model =
                RuleLifecycleModel.from(
                        result.value().orElseThrow().snapshot(), null, "");
        return ServiceResult.success(model);
    }

    public synchronized ServiceResult<DeleteConfirmation> requestDelete(String ruleId) {
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
                writer.deleteRule(
                        confirmation.ruleId(),
                        confirmation.targetDeviceId(),
                        confirmation.expectedTargetAuthorized());
        if (!deleted.isSuccess()) {
            return blocked(
                    deleted.userMessage(),
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized());
        }
        RuleMutationResult mutation = deleted.value().orElseThrow();
        if (!confirmation
                .targetDeviceId()
                .equals(mutation.affectedTargetDeviceId())) {
            String message =
                    "删除校验发现目标绑定不一致，未撤销任何目标授权；"
                            + "当前进入阻断故障状态。";
            try {
                writer.enterReadOnlyFault(message);
            } catch (RuntimeException exception) {
                message += " 无法持久化故障态，请停止后续写入。";
            }
            return blocked(
                    message,
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
            try {
                writer.enterReadOnlyFault(message);
            } catch (RuntimeException exception) {
                message += " 无法持久化故障态，请停止后续写入。";
            }
            lastUndoToken = null;
            return blocked(
                    message,
                    confirmation.ruleId(),
                    confirmation.targetDeviceId(),
                    confirmation.expectedTargetAuthorized());
        }

        lastUndoToken = null;
        clearTransientConflict();
        return refreshLocked(true);
    }

    private ServiceResult<RuleLifecycleModel> changeStatus(
            String ruleId, String expectedStatus, String nextStatus) {
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
                writer.changeRuleStatus(
                        ruleId,
                        rule.targetDeviceId(),
                        expectedStatus,
                        nextStatus,
                        rule.targetAuthorized());
        if (!result.isSuccess()) {
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
        model =
                RuleLifecycleModel.from(
                        result.value().orElseThrow().snapshot(), undoToken, "");
        return ServiceResult.success(model);
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
}
