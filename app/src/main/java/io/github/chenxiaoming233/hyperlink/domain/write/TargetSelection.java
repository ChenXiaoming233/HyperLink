package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.Objects;

public final class TargetSelection {
    private final SourceDevice source;
    private final TargetDevice target;
    private final String offlineWarning;
    private final boolean requiresTargetAuthorization;
    private final boolean valid;
    private final String validationMessage;

    TargetSelection(
            SourceDevice source,
            TargetDevice target,
            String offlineWarning,
            boolean requiresTargetAuthorization,
            boolean valid,
            String validationMessage) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.offlineWarning = Objects.requireNonNull(offlineWarning, "offlineWarning");
        this.requiresTargetAuthorization = requiresTargetAuthorization;
        this.valid = valid;
        this.validationMessage = Objects.requireNonNull(validationMessage, "validationMessage");
    }

    public SourceDevice source() {
        return source;
    }

    public TargetDevice target() {
        return target;
    }

    public String offlineWarning() {
        return offlineWarning;
    }

    public boolean requiresTargetAuthorization() {
        return requiresTargetAuthorization;
    }

    public boolean isValid() {
        return valid;
    }

    public String validationMessage() {
        return validationMessage;
    }

    public ConfirmedTargetSelection confirmAuthorization() {
        if (!valid) {
            throw new IllegalStateException(validationMessage);
        }
        return new ConfirmedTargetSelection(this);
    }
}
