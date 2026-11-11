package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.Objects;

public final class TargetSelectionFlow {
    private final NotificationFlowSnapshot snapshot;

    public TargetSelectionFlow(NotificationFlowSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public ServiceResult<TargetSelection> select(SourceDevice source, String targetDeviceId) {
        Objects.requireNonNull(source, "source");
        if (snapshot.staleCache()) {
            return ServiceResult.failure("当前为过期缓存快照，不能选择写入目标。");
        }
        if (snapshot.connectionState() != ConnectionState.CONNECTED
                && snapshot.connectionState() != ConnectionState.COMPATIBILITY_UNVERIFIED) {
            return ServiceResult.failure("当前无法读取完整目标列表，请重新连接后再选择。");
        }
        TargetDevice target = findTarget(snapshot, targetDeviceId);
        if (target == null) {
            return ServiceResult.failure("所选目标不在当前快照中，未执行任何写入。");
        }
        if (!target.supportsNotificationReceive()) {
            return ServiceResult.failure("所选目标不支持接收通知，未执行任何写入。");
        }
        boolean sourceIsTarget = source.deviceId().equals(target.deviceId());
        String validationMessage =
                sourceIsTarget ? "来源设备不能同时作为目标设备，请重新选择。" : "";
        return ServiceResult.success(
                new TargetSelection(
                        source,
                        target,
                        offlineWarning(target),
                        !target.targetAuthorized(),
                        !sourceIsTarget,
                        validationMessage));
    }

    private static TargetDevice findTarget(
            NotificationFlowSnapshot snapshot, String targetDeviceId) {
        for (TargetDevice target : snapshot.targets()) {
            if (target.deviceId().equals(targetDeviceId)) {
                return target;
            }
        }
        return null;
    }

    private static String offlineWarning(TargetDevice target) {
        StringBuilder warning = new StringBuilder();
        if (!target.online()) {
            warning.append("目标当前离线：通知不会排队，也不会自动重试。");
        } else if (!target.reachable()) {
            warning.append("目标在线但当前不可达：通知不会排队，也不会自动重试。");
        }
        return warning.toString();
    }
}
