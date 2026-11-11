package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class RuleSaveFeedback {
    private final String ruleId;
    private final List<String> notes;

    private RuleSaveFeedback(String ruleId, List<String> notes) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.notes = immutableNotes(notes);
    }

    static RuleSaveFeedback from(NotificationRule rule, List<String> notes) {
        return new RuleSaveFeedback(
                Objects.requireNonNull(rule, "rule").ruleId(), notes);
    }

    List<String> notes() {
        return notes;
    }

    String displayText() {
        StringBuilder message =
                new StringBuilder("已通过 SafeNotificationRuleWriter 写入独立 fake store：")
                        .append(ruleId)
                        .append("。未连接真实 VoiceAssist，通知不会真实发送。");
        if (notes.isEmpty()) {
            message.append("\n安全提示：无额外安全提示。");
            return message.toString();
        }
        message.append("\n安全提示：");
        for (String note : notes) {
            message.append("\n- ").append(note);
        }
        return message.toString();
    }

    private static List<String> immutableNotes(List<String> values) {
        Objects.requireNonNull(values, "notes");
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(Objects.requireNonNull(value, "note"));
        }
        return Collections.unmodifiableList(copy);
    }
}
