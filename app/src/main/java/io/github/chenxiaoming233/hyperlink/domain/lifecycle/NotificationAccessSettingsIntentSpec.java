package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import java.util.Objects;

public final class NotificationAccessSettingsIntentSpec {
    private static final String ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private final String action;

    private NotificationAccessSettingsIntentSpec(String action) {
        this.action = Objects.requireNonNull(action, "action");
    }

    public static NotificationAccessSettingsIntentSpec systemSettings() {
        return new NotificationAccessSettingsIntentSpec(ACTION);
    }

    public String action() {
        return action;
    }
}
