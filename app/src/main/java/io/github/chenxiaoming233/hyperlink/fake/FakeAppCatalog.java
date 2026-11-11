package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppCatalog;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppCatalogEntry;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FakeAppCatalog implements AppCatalog {
    private final List<AppCatalogEntry> apps;

    public FakeAppCatalog(List<AppCatalogEntry> apps) {
        Objects.requireNonNull(apps, "apps");
        List<AppCatalogEntry> copy = new ArrayList<>(apps.size());
        for (AppCatalogEntry app : apps) {
            copy.add(Objects.requireNonNull(app, "app"));
        }
        this.apps = Collections.unmodifiableList(copy);
    }

    public static FakeAppCatalog demo() {
        return new FakeAppCatalog(
                Arrays.asList(
                        new AppCatalogEntry("demo.app.chat", "Demo Chat", true),
                        new AppCatalogEntry("demo.app.social", "Demo Social", false),
                        new AppCatalogEntry("demo.app.notes", "Demo Notes", false),
                        new AppCatalogEntry("demo.app.camera", "Demo Camera", false)));
    }

    @Override
    public ServiceResult<List<AppCatalogEntry>> readApps() {
        return ServiceResult.success(apps);
    }
}
