package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.Objects;

public final class ConfirmedTargetSelection {
    private final TargetSelection selection;

    ConfirmedTargetSelection(TargetSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public SourceDevice source() {
        return selection.source();
    }

    public TargetDevice target() {
        return selection.target();
    }

    public String offlineWarning() {
        return selection.offlineWarning();
    }

    public boolean authorizationConfirmed() {
        return true;
    }
}
