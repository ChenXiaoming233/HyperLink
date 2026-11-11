package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Objects;

public final class RuleWriteReceipt {
    private final String serializedJson;

    public RuleWriteReceipt(String serializedJson) {
        this.serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
    }

    public String serializedJson() {
        return serializedJson;
    }
}
