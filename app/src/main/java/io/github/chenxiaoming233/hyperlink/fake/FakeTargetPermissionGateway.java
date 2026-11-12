package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.TargetPermissionGateway;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.TargetPermissionReceipt;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FakeTargetPermissionGateway implements TargetPermissionGateway {
    private final FakeRuleStoreGateway ruleStore;
    private int revokeCount;
    private String nextRevokeFailure = "";

    public FakeTargetPermissionGateway(FakeRuleStoreGateway ruleStore) {
        this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore");
    }

    @Override
    public synchronized ServiceResult<TargetPermissionReceipt> revoke(
            String targetDeviceId) {
        Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        revokeCount++;
        if (!nextRevokeFailure.isEmpty()) {
            String message = nextRevokeFailure;
            nextRevokeFailure = "";
            return ServiceResult.failure(message);
        }
        RuleStoreSnapshot current = ruleStore.snapshot();
        TargetDevice target = findTarget(current, targetDeviceId);
        if (target == null) {
            return ServiceResult.failure("目标不在当前 fake 权限快照中，无法撤销授权。");
        }
        Map<String, Boolean> authorizations = new LinkedHashMap<>();
        for (TargetDevice candidate : current.flowSnapshot().targets()) {
            authorizations.put(candidate.deviceId(), candidate.targetAuthorized());
        }
        authorizations.put(targetDeviceId, false);
        RuleStoreSnapshot revoked =
                current.withPermissions(
                        authorizations, current.flowSnapshot().notificationAccess());
        ruleStore.replaceSnapshot(revoked);
        TargetDevice verified = findTarget(ruleStore.snapshot(), targetDeviceId);
        if (verified == null || verified.targetAuthorized()) {
            return ServiceResult.failure("目标授权撤销后校验失败。");
        }
        return ServiceResult.success(new TargetPermissionReceipt(targetDeviceId, false));
    }

    public synchronized void failNextRevoke(String safeUserMessage) {
        nextRevokeFailure = Objects.requireNonNull(safeUserMessage, "safeUserMessage");
    }

    public synchronized int revokeCount() {
        return revokeCount;
    }

    private static TargetDevice findTarget(RuleStoreSnapshot snapshot, String targetId) {
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            if (target.deviceId().equals(targetId)) {
                return target;
            }
        }
        return null;
    }
}
