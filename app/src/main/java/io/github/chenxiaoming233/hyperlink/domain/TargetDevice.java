package io.github.chenxiaoming233.hyperlink.domain;

import java.util.Objects;

public final class TargetDevice {
    private final String deviceId;
    private final String displayName;
    private final DeviceType type;
    private final boolean online;
    private final boolean reachable;
    private final boolean targetAuthorized;
    private final boolean receiveCapability;

    public TargetDevice(
            String deviceId,
            String displayName,
            DeviceType type,
            boolean online,
            boolean reachable,
            boolean targetAuthorized,
            boolean receiveCapability) {
        this.deviceId = requireText(deviceId, "deviceId");
        this.displayName = requireText(displayName, "displayName");
        this.type = Objects.requireNonNull(type, "type");
        this.online = online;
        this.reachable = reachable;
        this.targetAuthorized = targetAuthorized;
        this.receiveCapability = receiveCapability;
    }

    public String deviceId() {
        return deviceId;
    }

    public String displayName() {
        return displayName;
    }

    public DeviceType type() {
        return type;
    }

    public boolean online() {
        return online;
    }

    public boolean reachable() {
        return reachable;
    }

    public boolean targetAuthorized() {
        return targetAuthorized;
    }

    public boolean receiveCapability() {
        return receiveCapability;
    }

    public boolean supportsNotificationReceive() {
        return receiveCapability && (type == DeviceType.PC || type == DeviceType.PAD
                || type == DeviceType.MAC);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
