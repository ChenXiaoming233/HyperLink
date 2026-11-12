package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.write.RuleModelReadiness;
import java.util.Objects;

final class RuleEditorMutationGate {
    private RuleModelReadiness readiness;

    RuleEditorMutationGate(RuleModelReadiness readiness) {
        update(readiness);
    }

    void update(RuleModelReadiness nextReadiness) {
        readiness = Objects.requireNonNull(nextReadiness, "readiness");
    }

    boolean writable() {
        return readiness.writable();
    }

    String reason() {
        return readiness.reason();
    }

    boolean edit(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!writable()) {
            return false;
        }
        action.run();
        return true;
    }

    boolean save(Runnable action) {
        return edit(action);
    }
}
