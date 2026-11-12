package io.github.chenxiaoming233.hyperlink.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class EditableViewTracker<T> {
    private final List<T> views = new ArrayList<>();

    void track(T view) {
        views.add(Objects.requireNonNull(view, "view"));
    }

    void clear() {
        views.clear();
    }

    int size() {
        return views.size();
    }

    void forEach(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        for (T view : views) {
            action.accept(view);
        }
    }
}
