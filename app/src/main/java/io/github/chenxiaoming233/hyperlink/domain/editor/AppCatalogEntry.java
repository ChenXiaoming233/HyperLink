package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.Objects;

public final class AppCatalogEntry {
    private final String packageName;
    private final String displayName;
    private final boolean launchable;

    public AppCatalogEntry(String packageName, String displayName, boolean launchable) {
        this.packageName = requireText(packageName, "packageName");
        this.displayName = displayName == null ? "" : displayName;
        this.launchable = launchable;
    }

    public String packageName() {
        return packageName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean launchable() {
        return launchable;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
