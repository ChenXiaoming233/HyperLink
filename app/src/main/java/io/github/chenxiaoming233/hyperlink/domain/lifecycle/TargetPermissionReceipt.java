package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import java.util.Objects;

public final class TargetPermissionReceipt {
    private final String targetDeviceId;
    private final boolean authorized;

    public TargetPermissionReceipt(String targetDeviceId, boolean authorized) {
        this.targetDeviceId = requireText(targetDeviceId, "targetDeviceId");
        this.authorized = authorized;
    }

    public String targetDeviceId() {
        return targetDeviceId;
    }

    public boolean authorized() {
        return authorized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
