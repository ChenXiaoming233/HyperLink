package io.github.chenxiaoming233.hyperlink.rpc;

import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RpcResponse {
    private final String requestId;
    private final boolean success;
    private final RpcErrorCode errorCode;
    private final long stateVersion;
    private final Map<String, Object> data;
    private final String userMessage;

    private RpcResponse(
            String requestId,
            boolean success,
            RpcErrorCode errorCode,
            long stateVersion,
            Map<String, Object> data,
            String userMessage) {
        this.requestId = requestId;
        this.success = success;
        this.errorCode = errorCode;
        this.stateVersion = stateVersion;
        this.data =
                Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.userMessage = userMessage;
    }

    public static RpcResponse success(String requestId, NotificationFlowSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionState", snapshot.connectionState().name());
        data.put("compatibilityUnverified", snapshot.compatibilityUnverified());
        return new RpcResponse(
                requestId,
                true,
                RpcErrorCode.NONE,
                snapshot.stateVersion(),
                data,
                userMessage(RpcErrorCode.NONE));
    }

    public static RpcResponse success(String requestId, RuntimeProbeSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(RuntimeProbeContract.KEY_STORE_READY, snapshot.storeReady());
        data.put(RuntimeProbeContract.KEY_RULE_STORE_READABLE, snapshot.ruleStoreReadable());
        data.put(RuntimeProbeContract.KEY_RULE_COUNT, snapshot.ruleCount());
        data.put(RuntimeProbeContract.KEY_DEVICE_INFO_READABLE, snapshot.deviceInfoReadable());
        data.put(RuntimeProbeContract.KEY_TARGET_COUNT, snapshot.targetCount());
        data.put(
                RuntimeProbeContract.KEY_NOTIFICATION_LISTENER_ACCESS,
                snapshot.notificationListenerAccess());
        return new RpcResponse(
                requestId,
                true,
                RpcErrorCode.NONE,
                0L,
                data,
                userMessage(RpcErrorCode.NONE));
    }

    public static RpcResponse failure(String requestId, RpcErrorCode errorCode) {
        return new RpcResponse(
                requestId,
                false,
                errorCode,
                0L,
                Collections.emptyMap(),
                userMessage(errorCode));
    }

    private static String userMessage(RpcErrorCode errorCode) {
        switch (errorCode) {
            case NONE:
                return "操作成功。";
            case PROTOCOL_MISMATCH:
                return "协议版本不匹配，请更新 HyperLink 后重试。";
            case AUTHENTICATION_FAILED:
                return "调用方认证失败。";
            case INVALID_REQUEST:
                return "请求无效。";
            case SERVICE_UNAVAILABLE:
                return "通知流转服务当前不可用。";
            default:
                throw new IllegalArgumentException("Unhandled RPC error: " + errorCode);
        }
    }

    public String requestId() {
        return requestId;
    }

    public boolean success() {
        return success;
    }

    public RpcErrorCode errorCode() {
        return errorCode;
    }

    public long stateVersion() {
        return stateVersion;
    }

    public Map<String, Object> data() {
        return data;
    }

    public String userMessage() {
        return userMessage;
    }
}
