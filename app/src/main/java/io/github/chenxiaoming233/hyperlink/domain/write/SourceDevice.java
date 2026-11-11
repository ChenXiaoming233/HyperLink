package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Objects;

public final class SourceDevice {
    private final String deviceId;
    private final String displayName;

    public SourceDevice(String deviceId, String displayName) {
        this.deviceId = requireText(deviceId, "deviceId");
        this.displayName = requireText(displayName, "displayName");
    }

    public String deviceId() {
        return deviceId;
    }

    public String displayName() {
        return displayName;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
