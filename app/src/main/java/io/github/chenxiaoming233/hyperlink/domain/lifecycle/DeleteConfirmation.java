package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import java.util.Objects;

public final class DeleteConfirmation {
    private final long sequence;
    private final String ruleId;
    private final String targetDeviceId;
    private final String targetDeviceName;
    private final boolean expectedTargetAuthorized;
    private final String prompt;

    DeleteConfirmation(
            long sequence,
            String ruleId,
            String targetDeviceId,
            String targetDeviceName,
            boolean expectedTargetAuthorized,
            String prompt) {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.sequence = sequence;
        this.ruleId = requireText(ruleId, "ruleId");
        this.targetDeviceId = requireText(targetDeviceId, "targetDeviceId");
        this.targetDeviceName = requireText(targetDeviceName, "targetDeviceName");
        this.expectedTargetAuthorized = expectedTargetAuthorized;
        this.prompt = requireText(prompt, "prompt");
    }

    long sequence() {
        return sequence;
    }

    public String ruleId() {
        return ruleId;
    }

    String targetDeviceId() {
        return targetDeviceId;
    }

    String targetDeviceName() {
        return targetDeviceName;
    }

    boolean expectedTargetAuthorized() {
        return expectedTargetAuthorized;
    }

    public String prompt() {
        return prompt;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
