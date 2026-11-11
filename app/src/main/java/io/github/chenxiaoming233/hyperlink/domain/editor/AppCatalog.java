package io.github.chenxiaoming233.hyperlink.domain.editor;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.List;

public interface AppCatalog {
    ServiceResult<List<AppCatalogEntry>> readApps();
}
