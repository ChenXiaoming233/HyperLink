package io.github.chenxiaoming233.hyperlink.domain.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.fake.FakeAppCatalog;
import java.util.List;
import org.junit.Test;

public final class AppSelectorModelTest {
    @Test
    public void defaultShowsLaunchableAndReferencedAppsOnly() {
        FakeAppCatalog catalog = FakeAppCatalog.demo();
        List<AppCatalogEntry> apps = catalog.readApps().value().orElseThrow();

        AppSelectorModel model =
                AppSelectorModel.fromCatalog(
                        apps,
                        List.of("demo.app.social", "demo.app.removed"),
                        List.of());

        assertTrue(find(model, "demo.app.chat").installed());
        assertTrue(find(model, "demo.app.social").installed());
        assertFalse(find(model, "demo.app.removed").installed());
        assertFalse(contains(model, "demo.app.notes"));
        assertFalse(contains(model, "demo.app.camera"));

        AppSelectorModel allInstalled = model.withShowAllInstalled(true);
        assertTrue(contains(allInstalled, "demo.app.notes"));
        assertTrue(contains(allInstalled, "demo.app.camera"));
        assertTrue(contains(allInstalled, "demo.app.removed"));
    }

    @Test
    public void referencedUninstalledAppKeepsPackageNameAndIsMarked() {
        AppSelectorModel model =
                AppSelectorModel.fromCatalog(
                        FakeAppCatalog.demo().readApps().value().orElseThrow(),
                        List.of("demo.app.removed"),
                        List.of("demo.app.removed"));

        AppSelectorModel.AppItem removed = find(model, "demo.app.removed");

        assertEquals("demo.app.removed", removed.displayName());
        assertEquals("未安装", removed.statusLabel());
        assertTrue(removed.selected());
        assertNotNull(removed.iconPlaceholder());
        assertFalse(removed.iconPlaceholder().isBlank());
        assertTrue(model.selectedPackages().contains("demo.app.removed"));
    }

    @Test
    public void searchMatchesNameAndPackageAndSupportsMultiSelect() {
        AppSelectorModel model =
                AppSelectorModel.fromCatalog(
                                FakeAppCatalog.demo().readApps().value().orElseThrow(),
                                List.of(),
                                List.of())
                        .withSearchQuery("demo chat")
                        .toggleSelected("demo.app.chat")
                        .toggleSelected("demo.app.social");

        assertEquals(1, model.items().size());
        assertEquals("demo.app.chat", model.items().get(0).packageName());
        assertEquals(2, model.selectedPackages().size());
        assertTrue(model.items().get(0).selected());

        AppSelectorModel byPackage = model.withSearchQuery("demo.app.social");
        assertEquals("demo.app.social", byPackage.items().get(0).packageName());
    }

    @Test
    public void blankCatalogNameFallsBackToPackageName() {
        AppSelectorModel model =
                AppSelectorModel.fromCatalog(
                        List.of(new AppCatalogEntry("demo.app.unnamed", "", true)),
                        List.of(),
                        List.of());

        assertEquals("demo.app.unnamed", model.items().get(0).displayName());
        assertFalse(model.items().get(0).statusLabel().isBlank());
    }

    private static AppSelectorModel.AppItem find(AppSelectorModel model, String packageName) {
        return model.items().stream()
                .filter(item -> item.packageName().equals(packageName))
                .findFirst()
                .orElseThrow();
    }

    private static boolean contains(AppSelectorModel model, String packageName) {
        return model.items().stream()
                .anyMatch(item -> item.packageName().equals(packageName));
    }
}
