package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import org.junit.Test;

public final class RuleSaveGateTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void unauthorizedTargetRequiresNamedExplicitConfirmationAndCanBeDenied() {
        RuleSaveGate gate = gate(selection("demo-target-c"));

        assertTrue(gate.confirmationRequired());
        assertTrue(gate.targetAuthorizationRequired());
        assertTrue(gate.promptMessage().contains("Demo-Pad"));
        assertTrue(gate.promptMessage().contains("允许将通知转发到该目标"));
        assertTrue(gate.promptMessage().contains("系统通知使用权"));

        RuleSaveGate.SaveDecision denied = gate.decide(false);
        assertFalse(denied.shouldSave());
        assertFalse(denied.confirmedSelection().isPresent());
        assertTrue(denied.message().contains("取消"));

        RuleSaveGate.SaveDecision accepted = gate.decide(true);
        assertTrue(accepted.shouldSave());
        assertTrue(accepted.confirmedSelection().isPresent());
    }

    @Test
    public void offlineTargetRequiresExplicitWarningBeforeSaving() {
        RuleSaveGate gate = gate(selection("demo-target-b"));

        assertTrue(gate.confirmationRequired());
        assertFalse(gate.targetAuthorizationRequired());
        assertTrue(gate.promptMessage().contains("离线"));
        assertTrue(gate.promptMessage().contains("不会排队"));
        assertTrue(gate.promptMessage().contains("不会自动重试"));
    }

    @Test
    public void ordinaryAuthorizedOnlineTargetDoesNotRequirePrompt() {
        RuleSaveGate gate = gate(selection("demo-target-a"));

        assertFalse(gate.confirmationRequired());
        assertFalse(gate.targetAuthorizationRequired());
        assertTrue(gate.decide(true).shouldSave());
        assertFalse(gate.decide(false).shouldSave());
    }

    private static TargetSelection selection(String targetId) {
        TargetSelectionFlow flow =
                new TargetSelectionFlow(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));
        return flow.select(SOURCE, targetId).value().orElseThrow();
    }

    private static RuleSaveGate gate(TargetSelection selection) {
        return RuleSaveGate.from(selection);
    }
}
