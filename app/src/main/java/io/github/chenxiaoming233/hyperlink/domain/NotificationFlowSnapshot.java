package io.github.chenxiaoming233.hyperlink.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class NotificationFlowSnapshot {
    private final ConnectionState connectionState;
    private final long stateVersion;
    private final boolean compatibilityUnverified;
    private final List<TargetDevice> targets;
    private final List<NotificationRule> rules;
    private final NotificationAccessState notificationAccess;
    private final boolean staleCache;

    public NotificationFlowSnapshot(
            ConnectionState connectionState,
            long stateVersion,
            boolean compatibilityUnverified,
            List<TargetDevice> targets,
            List<NotificationRule> rules,
            NotificationAccessState notificationAccess,
            boolean staleCache) {
        this.connectionState = Objects.requireNonNull(connectionState, "connectionState");
        if (stateVersion <= 0) {
            throw new IllegalArgumentException("stateVersion must be positive");
        }
        this.stateVersion = stateVersion;
        this.compatibilityUnverified = compatibilityUnverified;
        this.targets = immutableCopy(targets, "targets");
        this.rules = immutableCopy(rules, "rules");
        this.notificationAccess =
                Objects.requireNonNull(notificationAccess, "notificationAccess");
        this.staleCache = staleCache;
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

    public List<TargetDevice> targets() {
        return targets;
    }

    public List<NotificationRule> rules() {
        return rules;
    }

    public NotificationAccessState notificationAccess() {
        return notificationAccess;
    }

    public boolean staleCache() {
        return staleCache;
    }

    public NotificationFlowSnapshot withConnectionState(
            ConnectionState nextState, long nextStateVersion) {
        return new NotificationFlowSnapshot(
                nextState,
                nextStateVersion,
                nextState == ConnectionState.COMPATIBILITY_UNVERIFIED,
                targets,
                rules,
                notificationAccess,
                staleCache);
    }

    public NotificationFlowSnapshot withNotificationAccess(
            NotificationAccessState nextNotificationAccess) {
        return new NotificationFlowSnapshot(
                connectionState,
                stateVersion + 1L,
                compatibilityUnverified,
                targets,
                rules,
                nextNotificationAccess,
                staleCache);
    }

    public NotificationFlowSnapshot withStaleCache(boolean nextStaleCache) {
        return new NotificationFlowSnapshot(
                connectionState,
                stateVersion + 1L,
                compatibilityUnverified,
                targets,
                rules,
                notificationAccess,
                nextStaleCache);
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }
}
