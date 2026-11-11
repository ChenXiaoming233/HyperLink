package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import org.junit.Test;

public final class TargetSelectionFlowTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void offlineTargetSelectionKeepsWarningForLaterCreation() {
        TargetSelectionFlow flow =
                new TargetSelectionFlow(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));

        TargetSelection selection =
                flow.select(SOURCE, "demo-target-b").value().orElseThrow();

        assertFalse(selection.requiresTargetAuthorization());
        assertTrue(selection.offlineWarning().contains("不会排队"));
        assertTrue(selection.offlineWarning().contains("不会自动重试"));
        assertTrue(selection.confirmAuthorization().offlineWarning().contains("离线"));
    }

    @Test
    public void firstUseOfUnauthorizedTargetRequiresExplicitConfirmation() {
        TargetSelectionFlow flow =
                new TargetSelectionFlow(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));

        TargetSelection selection =
                flow.select(SOURCE, "demo-target-c").value().orElseThrow();

        assertTrue(selection.requiresTargetAuthorization());
        ConfirmedTargetSelection confirmed = selection.confirmAuthorization();
        assertTrue(confirmed.authorizationConfirmed());
    }

    @Test
    public void missingOrIncapableTargetCannotBeSelected() {
        TargetSelectionFlow flow =
                new TargetSelectionFlow(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));

        assertFalse(flow.select(SOURCE, "demo-target-missing").isSuccess());
        assertFalse(flow.select(SOURCE, "demo-target-d").isSuccess());
        assertFalse(flow.select(SOURCE, "demo-target-e").isSuccess());
    }

    @Test
    public void sourceCannotBeTheSelectedTarget() {
        TargetSelectionFlow flow =
                new TargetSelectionFlow(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));

        TargetSelection selection =
                flow.select(new SourceDevice("demo-target-a", "Demo-PC"), "demo-target-a")
                        .value()
                        .orElseThrow();

        assertFalse(selection.isValid());
        assertTrue(selection.validationMessage().contains("来源设备"));
    }
}
