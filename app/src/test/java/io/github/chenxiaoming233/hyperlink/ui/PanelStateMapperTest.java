package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import org.junit.Test;

public final class PanelStateMapperTest {
    @Test
    public void mapsEveryConnectionStateToSafeUserCopy() {
        for (ConnectionState state : ConnectionState.values()) {
            PanelDisplayModel model =
                    PanelStateMapper.map(FakeNotificationFlowService.emptySnapshot(state));

            assertNotNull(state.name(), model.title());
            assertNotNull(state.name(), model.message());
            assertFalse(state.name(), model.title().isBlank());
            assertFalse(state.name(), model.message().isBlank());
        }
    }

    @Test
    public void capabilityFailureKeepsPanelReadOnlyAndEnablesRetry() {
        PanelDisplayModel model =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(
                                ConnectionState.CAPABILITY_INSUFFICIENT));

        assertTrue(model.readOnly());
        assertTrue(model.retryEnabled());
    }

    @Test
    public void connectedStateIsReadOnlyWithoutRetry() {
        PanelDisplayModel model =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(ConnectionState.CONNECTED));

        assertTrue(model.readOnly());
        assertFalse(model.retryEnabled());
        assertFalse(model.compatibilityBadgeVisible());
    }

    @Test
    public void unknownCompatibilityIsAlwaysVisible() {
        PanelDisplayModel model =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(
                                ConnectionState.COMPATIBILITY_UNVERIFIED));

        assertTrue(model.compatibilityBadgeVisible());
    }

    @Test
    public void serviceFailureProducesRetryableSafeCopy() {
        PanelDisplayModel model = PanelStateMapper.mapFailure("服务暂时不可用。");

        assertEquals("读取失败", model.title());
        assertEquals("服务暂时不可用。", model.message());
        assertTrue(model.readOnly());
        assertTrue(model.retryEnabled());
        assertFalse(model.compatibilityBadgeVisible());
    }
}
