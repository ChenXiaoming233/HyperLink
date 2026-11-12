package io.github.chenxiaoming233.hyperlink.rpc;

public final class RuntimeProbeContract {
    public static final String METHOD = "hyperlink.notificationFlow.probe.v1";
    public static final String EXTRA_PROTOCOL_VERSION = "protocolVersion";
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_USER_MESSAGE = "userMessage";
    public static final String KEY_STORE_READY = "storeReady";
    public static final String KEY_RULE_STORE_READABLE = "ruleStoreReadable";
    public static final String KEY_RULE_COUNT = "ruleCount";
    public static final String KEY_DEVICE_INFO_READABLE = "deviceInfoReadable";
    public static final String KEY_TARGET_COUNT = "targetCount";
    public static final String KEY_NOTIFICATION_LISTENER_ACCESS = "notificationListenerAccess";

    private RuntimeProbeContract() {}
}
