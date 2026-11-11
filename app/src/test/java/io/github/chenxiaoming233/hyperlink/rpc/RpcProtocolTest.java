package io.github.chenxiaoming233.hyperlink.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RpcProtocolTest {
    @Test
    public void currentVersionIsSupported() {
        RpcProtocol protocol = new RpcProtocol(1);

        assertTrue(protocol.supports(1));
        assertEquals(1, protocol.currentVersion());
    }

    @Test
    public void nonMatchingVersionIsRejected() {
        RpcProtocol protocol = new RpcProtocol(1);

        assertFalse(protocol.supports(0));
        assertFalse(protocol.supports(2));
    }
}
