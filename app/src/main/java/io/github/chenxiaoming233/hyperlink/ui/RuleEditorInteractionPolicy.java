package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.editor.AppConditionEditorModel;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleModelReadiness;
import java.util.Objects;

final class RuleEditorInteractionPolicy {
    private final RuleEditorMutationGate mutationGate;

    RuleEditorInteractionPolicy(RuleModelReadiness readiness) {
        mutationGate = new RuleEditorMutationGate(readiness);
    }

    void update(RuleModelReadiness readiness) {
        mutationGate.update(readiness);
    }

    boolean appCheckboxEnabled(boolean hasAppScope) {
        return hasAppScope && mutationGate.writable();
    }

    AppSelection selectApp(AppConditionEditorModel editor, String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        if (editor == null) {
            return AppSelection.rejected(null, "请先在规则分支中选定一个 app 条件作用域。");
        }
        if (!mutationGate.writable()) {
            return AppSelection.rejected(
                    editor, "整份规则模型为只读：" + mutationGate.reason());
        }
        return AppSelection.accepted(editor.togglePackage(packageName));
    }

    boolean canEdit() {
        return mutationGate.writable();
    }

    boolean saveAllowed() {
        return mutationGate.writable();
    }

    String reason() {
        return mutationGate.reason();
    }

    static final class AppSelection {
        private final boolean accepted;
        private final AppConditionEditorModel editor;
        private final String message;

        private AppSelection(
                boolean accepted, AppConditionEditorModel editor, String message) {
            this.accepted = accepted;
            this.editor = editor;
            this.message = Objects.requireNonNull(message, "message");
        }

        static AppSelection accepted(AppConditionEditorModel editor) {
            return new AppSelection(
                    true, Objects.requireNonNull(editor, "editor"), "");
        }

        static AppSelection rejected(
                AppConditionEditorModel editor, String message) {
            return new AppSelection(false, editor, message);
        }

        boolean accepted() {
            return accepted;
        }

        AppConditionEditorModel editor() {
            return editor;
        }

        String message() {
            return message;
        }
    }
}
