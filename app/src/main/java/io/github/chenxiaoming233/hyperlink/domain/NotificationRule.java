package io.github.chenxiaoming233.hyperlink.domain;

import java.util.Objects;

public final class NotificationRule {
    private final String ruleId;
    private final String sourceDeviceId;
    private final String sourceDeviceName;
    private final String targetDeviceId;
    private final String targetDeviceName;
    private final String status;
    private final String summary;
    private final String dslJson;

    public NotificationRule(
            String ruleId,
            String sourceDeviceId,
            String sourceDeviceName,
            String targetDeviceId,
            String targetDeviceName,
            String status,
            String summary,
            String dslJson) {
        this.ruleId = requireText(ruleId, "ruleId");
        this.sourceDeviceId = requireText(sourceDeviceId, "sourceDeviceId");
        this.sourceDeviceName = requireText(sourceDeviceName, "sourceDeviceName");
        this.targetDeviceId = requireText(targetDeviceId, "targetDeviceId");
        this.targetDeviceName = requireText(targetDeviceName, "targetDeviceName");
        this.status = requireText(status, "status");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.dslJson = Objects.requireNonNull(dslJson, "dslJson");
    }

    public String ruleId() {
        return ruleId;
    }

    public String sourceDeviceId() {
        return sourceDeviceId;
    }

    public String sourceDeviceName() {
        return sourceDeviceName;
    }

    public String targetDeviceId() {
        return targetDeviceId;
    }

    public String targetDeviceName() {
        return targetDeviceName;
    }

    public String status() {
        return status;
    }

    public String summary() {
        return summary;
    }

    public String dslJson() {
        return dslJson;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
