package io.github.chenxiaoming233.hyperlink.domain;

import java.util.Objects;
import java.util.Optional;

public final class ServiceResult<T> {
    private final T value;
    private final String userMessage;

    private ServiceResult(T value, String userMessage) {
        this.value = value;
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
    }

    public static <T> ServiceResult<T> success(T value) {
        return new ServiceResult<>(Objects.requireNonNull(value, "value"), "");
    }

    public static <T> ServiceResult<T> failure(String userMessage) {
        return new ServiceResult<>(null, userMessage);
    }

    public boolean isSuccess() {
        return value != null;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    public String userMessage() {
        return userMessage;
    }
}
