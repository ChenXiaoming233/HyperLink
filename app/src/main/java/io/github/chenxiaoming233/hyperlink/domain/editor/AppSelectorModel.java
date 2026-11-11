package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AppSelectorModel {
    private final List<AppCatalogEntry> catalog;
    private final Set<String> referencedPackages;
    private final Set<String> selectedPackages;
    private final boolean showAllInstalled;
    private final String searchQuery;

    private AppSelectorModel(
            List<AppCatalogEntry> catalog,
            Set<String> referencedPackages,
            Set<String> selectedPackages,
            boolean showAllInstalled,
            String searchQuery) {
        this.catalog = immutableCatalog(catalog);
        this.referencedPackages = immutablePackages(referencedPackages);
        this.selectedPackages = immutablePackages(selectedPackages);
        this.showAllInstalled = showAllInstalled;
        this.searchQuery = Objects.requireNonNull(searchQuery, "searchQuery");
    }

    public static AppSelectorModel fromCatalog(
            List<AppCatalogEntry> catalog,
            List<String> referencedPackages,
            List<String> selectedPackages) {
        return new AppSelectorModel(
                catalog,
                new LinkedHashSet<>(referencedPackages),
                new LinkedHashSet<>(selectedPackages),
                false,
                "");
    }

    public AppSelectorModel withShowAllInstalled(boolean nextValue) {
        return new AppSelectorModel(
                catalog, referencedPackages, selectedPackages, nextValue, searchQuery);
    }

    public AppSelectorModel withSearchQuery(String nextQuery) {
        return new AppSelectorModel(
                catalog,
                referencedPackages,
                selectedPackages,
                showAllInstalled,
                Objects.requireNonNull(nextQuery, "nextQuery"));
    }

    public AppSelectorModel toggleSelected(String packageName) {
        Set<String> next = new LinkedHashSet<>(selectedPackages);
        if (!next.remove(packageName)) {
            next.add(Objects.requireNonNull(packageName, "packageName"));
        }
        return new AppSelectorModel(
                catalog, referencedPackages, next, showAllInstalled, searchQuery);
    }

    public boolean showAllInstalled() {
        return showAllInstalled;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public Set<String> selectedPackages() {
        return selectedPackages;
    }

    public List<AppItem> items() {
        Map<String, AppCatalogEntry> installed = new LinkedHashMap<>();
        for (AppCatalogEntry entry : catalog) {
            installed.put(entry.packageName(), entry);
        }
        Set<String> allPackages = new LinkedHashSet<>(installed.keySet());
        allPackages.addAll(referencedPackages);
        allPackages.addAll(selectedPackages);

        String normalizedQuery = searchQuery.trim().toLowerCase(Locale.ROOT);
        List<AppItem> items = new ArrayList<>();
        for (String packageName : allPackages) {
            AppCatalogEntry entry = installed.get(packageName);
            boolean isInstalled = entry != null;
            boolean referenced = referencedPackages.contains(packageName);
            boolean selected = selectedPackages.contains(packageName);
            boolean launchable = isInstalled && entry.launchable();
            if (!visible(isInstalled, referenced || selected, launchable)) {
                continue;
            }
            String displayName =
                    isInstalled && !entry.displayName().trim().isEmpty()
                            ? entry.displayName()
                            : packageName;
            if (!matches(normalizedQuery, displayName, packageName)) {
                continue;
            }
            items.add(
                    new AppItem(
                            packageName,
                            displayName,
                            isInstalled,
                            launchable,
                            selected));
        }
        items.sort(
                Comparator.comparing(AppItem::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AppItem::packageName));
        return Collections.unmodifiableList(items);
    }

    private boolean visible(boolean installed, boolean referenced, boolean launchable) {
        if (showAllInstalled) {
            return installed || referenced;
        }
        return referenced || (installed && launchable);
    }

    private static boolean matches(
            String normalizedQuery, String displayName, String packageName) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        return displayName.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || packageName.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static List<AppCatalogEntry> immutableCatalog(List<AppCatalogEntry> values) {
        Objects.requireNonNull(values, "catalog");
        List<AppCatalogEntry> copy = new ArrayList<>(values.size());
        for (AppCatalogEntry value : values) {
            copy.add(Objects.requireNonNull(value, "app"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Set<String> immutablePackages(Set<String> values) {
        Objects.requireNonNull(values, "packages");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be blank");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    public static final class AppItem {
        private final String packageName;
        private final String displayName;
        private final boolean installed;
        private final boolean launchable;
        private final boolean selected;

        private AppItem(
                String packageName,
                String displayName,
                boolean installed,
                boolean launchable,
                boolean selected) {
            this.packageName = packageName;
            this.displayName = displayName;
            this.installed = installed;
            this.launchable = launchable;
            this.selected = selected;
        }

        public String packageName() {
            return packageName;
        }

        public String displayName() {
            return displayName;
        }

        public boolean installed() {
            return installed;
        }

        public boolean launchable() {
            return launchable;
        }

        public boolean selected() {
            return selected;
        }

        public String statusLabel() {
            if (!installed) {
                return "未安装";
            }
            return launchable ? "可启动" : "不可启动";
        }

        public String iconPlaceholder() {
            return displayName.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }
}
