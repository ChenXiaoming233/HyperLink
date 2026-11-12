package io.github.chenxiaoming233.hyperlink.rpc;

public final class RuntimeProbeSnapshot {
    private final boolean storeReady;
    private final boolean ruleStoreReadable;
    private final int ruleCount;
    private final boolean deviceInfoReadable;
    private final int targetCount;
    private final boolean notificationListenerAccess;

    public RuntimeProbeSnapshot(
            boolean storeReady,
            boolean ruleStoreReadable,
            int ruleCount,
            boolean deviceInfoReadable,
            int targetCount,
            boolean notificationListenerAccess) {
        if (ruleCount < 0 || targetCount < 0) {
            throw new IllegalArgumentException("probe counts must not be negative");
        }
        this.storeReady = storeReady;
        this.ruleStoreReadable = ruleStoreReadable;
        this.ruleCount = ruleCount;
        this.deviceInfoReadable = deviceInfoReadable;
        this.targetCount = targetCount;
        this.notificationListenerAccess = notificationListenerAccess;
    }

    public boolean storeReady() {
        return storeReady;
    }

    public boolean ruleStoreReadable() {
        return ruleStoreReadable;
    }

    public int ruleCount() {
        return ruleCount;
    }

    public boolean deviceInfoReadable() {
        return deviceInfoReadable;
    }

    public int targetCount() {
        return targetCount;
    }

    public boolean notificationListenerAccess() {
        return notificationListenerAccess;
    }
}
