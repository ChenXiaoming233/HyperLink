package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import java.util.Objects;

public final class RuleUndoToken {
    private final long sequence;
    private final String ruleId;
    private final String previousStatus;
    private final String nextStatus;
    private final String targetDeviceId;
    private final boolean targetAuthorized;
    private final long expiresAtMillis;

    RuleUndoToken(
            long sequence,
            String ruleId,
            String previousStatus,
            String nextStatus,
            String targetDeviceId,
            boolean targetAuthorized,
            long expiresAtMillis) {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.sequence = sequence;
        this.ruleId = requireText(ruleId, "ruleId");
        this.previousStatus = requireText(previousStatus, "previousStatus");
        this.nextStatus = requireText(nextStatus, "nextStatus");
        this.targetDeviceId = requireText(targetDeviceId, "targetDeviceId");
        this.targetAuthorized = targetAuthorized;
        this.expiresAtMillis = expiresAtMillis;
    }

    public long sequence() {
        return sequence;
    }

    public String ruleId() {
        return ruleId;
    }

    String previousStatus() {
        return previousStatus;
    }

    String nextStatus() {
        return nextStatus;
    }

    String targetDeviceId() {
        return targetDeviceId;
    }

    boolean targetAuthorized() {
        return targetAuthorized;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
