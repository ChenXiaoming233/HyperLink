package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Objects;

public final class CapabilityGap {
    private final CapabilityKind kind;
    private final String descriptor;

    CapabilityGap(CapabilityKind kind, String descriptor) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public CapabilityKind kind() {
        return kind;
    }

    public String descriptor() {
        return descriptor;
    }
}
