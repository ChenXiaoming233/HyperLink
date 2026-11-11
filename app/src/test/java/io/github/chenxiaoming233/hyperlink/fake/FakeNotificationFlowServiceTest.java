package io.github.chenxiaoming233.hyperlink.fake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import org.junit.Test;

public final class FakeNotificationFlowServiceTest {
    @Test
    public void exposesEveryRequiredPanelState() {
        for (ConnectionState state : ConnectionState.values()) {
            FakeNotificationFlowService service = new FakeNotificationFlowService(state);
            ServiceResult<NotificationFlowSnapshot> result = service.readSnapshot();

            assertTrue(state.name(), result.isSuccess());
            assertEquals(state, result.value().orElseThrow().connectionState());
            assertTrue(state.name(), result.value().orElseThrow().stateVersion() > 0);
        }
    }

    @Test
    public void compatibilityStateCarriesWarningFlag() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.COMPATIBILITY_UNVERIFIED);

        NotificationFlowSnapshot snapshot = service.readSnapshot().value().orElseThrow();

        assertTrue(snapshot.compatibilityUnverified());
    }

    @Test
    public void stateChangesAdvanceStateVersion() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        long connectedVersion = service.readSnapshot().value().orElseThrow().stateVersion();

        service.setConnectionState(ConnectionState.TARGET_PROCESS_UNREACHABLE);
        long unreachableVersion = service.readSnapshot().value().orElseThrow().stateVersion();

        assertTrue(unreachableVersion > connectedVersion);
    }
}
