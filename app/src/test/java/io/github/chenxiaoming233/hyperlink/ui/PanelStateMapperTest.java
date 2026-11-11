package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import org.junit.Test;

public final class PanelStateMapperTest {
    @Test
    public void mapsEveryConnectionStateToSafeUserCopy() {
        for (ConnectionState state : ConnectionState.values()) {
            PanelDisplayModel model = PanelStateMapper.map(state, true);

            assertNotNull(state.name(), model.title());
            assertNotNull(state.name(), model.message());
            assertFalse(state.name(), model.title().isBlank());
            assertFalse(state.name(), model.message().isBlank());
        }
    }

    @Test
    public void capabilityFailureDisablesManagementAndEnablesRetry() {
        PanelDisplayModel model =
                PanelStateMapper.map(ConnectionState.CAPABILITY_INSUFFICIENT, false);

        assertFalse(model.managementEnabled());
        assertTrue(model.retryEnabled());
    }

    @Test
    public void connectedStateEnablesManagement() {
        PanelDisplayModel model = PanelStateMapper.map(ConnectionState.CONNECTED, false);

        assertTrue(model.managementEnabled());
        assertFalse(model.compatibilityBadgeVisible());
    }

    @Test
    public void unknownCompatibilityIsAlwaysVisible() {
        PanelDisplayModel model = PanelStateMapper.map(ConnectionState.CONNECTED, true);

        assertTrue(model.compatibilityBadgeVisible());
    }

    @Test
    public void serviceFailureProducesRetryableSafeCopy() {
        PanelDisplayModel model = PanelStateMapper.mapFailure("服务暂时不可用。");

        assertEquals("读取失败", model.title());
        assertEquals("服务暂时不可用。", model.message());
        assertFalse(model.managementEnabled());
        assertTrue(model.retryEnabled());
        assertFalse(model.compatibilityBadgeVisible());
    }
}
