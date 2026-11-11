package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.List;
import org.junit.Test;

public final class RuleSaveFeedbackTest {
    @Test
    public void keepsEverySafeWriteNoteReadableInOrder() {
        NotificationRule rule =
                new NotificationRule(
                        "demo-rule-feedback",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "active",
                        "演示摘要",
                        "{\"ruleText\":\"演示摘要\"}",
                        100L);
        List<String> notes =
                List.of(
                        "目标当前离线：通知不会排队，也不会自动重试。",
                        "目标尚未授权，规则不会正常转发。",
                        "系统通知使用权缺失，规则转发受阻。",
                        "兼容性未经完整验收。");

        RuleSaveFeedback feedback = RuleSaveFeedback.from(rule, notes);

        assertEquals(notes, feedback.notes());
        assertTrue(feedback.displayText().contains("demo-rule-feedback"));
        for (String note : notes) {
            assertTrue(note, feedback.displayText().contains(note));
        }
        assertTrue(feedback.displayText().contains("安全提示"));
    }

    @Test
    public void emptyNotesStillProduceExplicitSuccessCopy() {
        NotificationRule rule =
                new NotificationRule(
                        "demo-rule-feedback-empty",
                        "demo-source-phone",
                        "Demo-Phone",
                        "demo-target-a",
                        "Demo-PC",
                        "active",
                        "演示摘要",
                        "{\"ruleText\":\"演示摘要\"}",
                        100L);

        RuleSaveFeedback feedback = RuleSaveFeedback.from(rule, List.of());

        assertTrue(feedback.notes().isEmpty());
        assertTrue(feedback.displayText().contains("无额外安全提示"));
    }
}
