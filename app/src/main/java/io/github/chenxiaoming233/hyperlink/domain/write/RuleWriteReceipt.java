package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Objects;

public final class RuleWriteReceipt {
    private final String serializedJson;
    private final long storeVersion;

    public RuleWriteReceipt(String serializedJson) {
        this(serializedJson, 1L);
    }

    public RuleWriteReceipt(String serializedJson, long storeVersion) {
        this.serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
        if (storeVersion <= 0) {
            throw new IllegalArgumentException("storeVersion must be positive");
        }
        this.storeVersion = storeVersion;
    }

    public String serializedJson() {
        return serializedJson;
    }

    public long storeVersion() {
        return storeVersion;
    }
}
