package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;

public interface TargetPermissionGateway {
    ServiceResult<TargetPermissionReceipt> revoke(String targetDeviceId);
}
