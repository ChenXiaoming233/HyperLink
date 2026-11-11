package io.github.chenxiaoming233.hyperlink.ui;

import java.util.Objects;

public final class PanelDisplayModel {
    private final String title;
    private final String message;
    private final boolean managementEnabled;
    private final boolean retryEnabled;
    private final boolean compatibilityBadgeVisible;

    public PanelDisplayModel(
            String title,
            String message,
            boolean managementEnabled,
            boolean retryEnabled,
            boolean compatibilityBadgeVisible) {
        this.title = Objects.requireNonNull(title, "title");
        this.message = Objects.requireNonNull(message, "message");
        this.managementEnabled = managementEnabled;
        this.retryEnabled = retryEnabled;
        this.compatibilityBadgeVisible = compatibilityBadgeVisible;
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    public boolean managementEnabled() {
        return managementEnabled;
    }

    public boolean retryEnabled() {
        return retryEnabled;
    }

    public boolean compatibilityBadgeVisible() {
        return compatibilityBadgeVisible;
    }
}
