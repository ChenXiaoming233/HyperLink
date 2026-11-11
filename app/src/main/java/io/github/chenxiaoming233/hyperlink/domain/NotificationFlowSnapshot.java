package io.github.chenxiaoming233.hyperlink.domain;

import java.util.Objects;

public final class NotificationFlowSnapshot {
    private final ConnectionState connectionState;
    private final long stateVersion;
    private final boolean compatibilityUnverified;

    public NotificationFlowSnapshot(
            ConnectionState connectionState, long stateVersion, boolean compatibilityUnverified) {
        this.connectionState = Objects.requireNonNull(connectionState, "connectionState");
        if (stateVersion <= 0) {
            throw new IllegalArgumentException("stateVersion must be positive");
        }
        this.stateVersion = stateVersion;
        this.compatibilityUnverified = compatibilityUnverified;
    }

    public ConnectionState connectionState() {
        return connectionState;
    }

    public long stateVersion() {
        return stateVersion;
    }

    public boolean compatibilityUnverified() {
        return compatibilityUnverified;
    }
}
