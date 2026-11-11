package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelection;
import java.util.Objects;
import java.util.Optional;

final class RuleSaveGate {
    private final TargetSelection selection;

    private RuleSaveGate(TargetSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    static RuleSaveGate from(TargetSelection selection) {
        return new RuleSaveGate(selection);
    }

    boolean confirmationRequired() {
        return selection.isValid()
                && (selection.requiresTargetAuthorization()
                        || !selection.offlineWarning().isEmpty());
    }

    boolean targetAuthorizationRequired() {
        return selection.requiresTargetAuthorization();
    }

    String promptTitle() {
        if (selection.requiresTargetAuthorization()
                && !selection.offlineWarning().isEmpty()) {
            return "确认目标授权与离线配置";
        }
        if (selection.requiresTargetAuthorization()) {
            return "确认目标授权";
        }
        if (!selection.offlineWarning().isEmpty()) {
            return "确认离线目标";
        }
        return "";
    }

    String promptMessage() {
        StringBuilder message = new StringBuilder();
        if (selection.requiresTargetAuthorization()) {
            message.append("目标 ")
                    .append(selection.target().displayName())
                    .append(" 尚未授权。确认表示允许将通知转发到该目标；")
                    .append("目标授权与系统通知使用权相互独立，仍需单独具备系统通知使用权。");
        }
        if (!selection.offlineWarning().isEmpty()) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(selection.offlineWarning());
        }
        return message.toString();
    }

    SaveDecision decide(boolean userAccepted) {
        if (!selection.isValid()) {
            return SaveDecision.cancelled(selection.validationMessage());
        }
        if (!userAccepted) {
            return SaveDecision.cancelled("已取消保存，未写入任何 fake 规则。");
        }
        return SaveDecision.confirmed(selection.confirmAuthorization());
    }

    static final class SaveDecision {
        private final boolean shouldSave;
        private final ConfirmedTargetSelection confirmedSelection;
        private final String message;

        private SaveDecision(
                boolean shouldSave,
                ConfirmedTargetSelection confirmedSelection,
                String message) {
            this.shouldSave = shouldSave;
            this.confirmedSelection = confirmedSelection;
            this.message = Objects.requireNonNull(message, "message");
        }

        static SaveDecision confirmed(ConfirmedTargetSelection selection) {
            return new SaveDecision(
                    true, Objects.requireNonNull(selection, "selection"), "");
        }

        static SaveDecision cancelled(String message) {
            return new SaveDecision(false, null, message);
        }

        boolean shouldSave() {
            return shouldSave;
        }

        Optional<ConfirmedTargetSelection> confirmedSelection() {
            return Optional.ofNullable(confirmedSelection);
        }

        String message() {
            return message;
        }
    }
}
