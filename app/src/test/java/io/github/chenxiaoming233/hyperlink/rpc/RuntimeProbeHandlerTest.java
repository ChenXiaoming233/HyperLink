package io.github.chenxiaoming233.hyperlink.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class RuntimeProbeHandlerTest {
    private static final String PANEL_PACKAGE = "io.github.chenxiaoming233.hyperlink";
    private static final String PANEL_SIGNER = "Aa11Bb22Cc33";

    @Test
    public void authenticatedProbeReturnsOnlySanitizedRuntimeFacts() {
        CallerEvidence caller = validCaller();
        RuntimeProbeHandler handler = newHandler(caller, () -> ServiceResult.success(readySnapshot()));

        RpcResponse response =
                handler.handle(
                        probeRequest(RpcProtocol.VERSION, RpcOperation.PROBE_TARGET),
                        callerIdentity(caller));

        assertTrue(response.success());
        assertEquals(RpcErrorCode.NONE, response.errorCode());
        assertEquals(true, response.data().get("storeReady"));
        assertEquals(true, response.data().get("ruleStoreReadable"));
        assertEquals(3, response.data().get("ruleCount"));
        assertEquals(true, response.data().get("deviceInfoReadable"));
        assertEquals(2, response.data().get("targetCount"));
        assertEquals(true, response.data().get("notificationListenerAccess"));
        assertFalse(response.data().containsKey("ruleId"));
        assertFalse(response.data().containsKey("deviceId"));
    }

    @Test
    public void authenticationFailureRunsBeforeProtocolAndProbeRead() {
        CallerEvidence attacker =
                new CallerEvidence(10456, List.of("com.example.attacker"), Set.of(PANEL_SIGNER));
        CountingProbe probe = new CountingProbe();
        RuntimeProbeHandler handler = newHandler(attacker, probe);

        RpcResponse response =
                handler.handle(
                        probeRequest(RpcProtocol.VERSION + 1, RpcOperation.PROBE_TARGET),
                        callerIdentity(attacker));

        assertEquals(RpcErrorCode.AUTHENTICATION_FAILED, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, probe.calls);
    }

    @Test
    public void protocolMismatchDoesNotReadProbeData() {
        CallerEvidence caller = validCaller();
        CountingProbe probe = new CountingProbe();
        RuntimeProbeHandler handler = newHandler(caller, probe);

        RpcResponse response =
                handler.handle(
                        probeRequest(RpcProtocol.VERSION + 1, RpcOperation.PROBE_TARGET),
                        callerIdentity(caller));

        assertEquals(RpcErrorCode.PROTOCOL_MISMATCH, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, probe.calls);
    }

    @Test
    public void unrelatedOperationIsRejectedWithoutReadingProbeData() {
        CallerEvidence caller = validCaller();
        CountingProbe probe = new CountingProbe();
        RuntimeProbeHandler handler = newHandler(caller, probe);

        RpcResponse response =
                handler.handle(
                        probeRequest(RpcProtocol.VERSION, RpcOperation.READ_SNAPSHOT),
                        callerIdentity(caller));

        assertEquals(RpcErrorCode.INVALID_REQUEST, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, probe.calls);
    }

    private static RuntimeProbeHandler newHandler(
            CallerEvidence evidence, RuntimeProbeSource probe) {
        return new RuntimeProbeHandler(
                new RpcProtocol(RpcProtocol.VERSION),
                identity -> evidence,
                new PinnedCallerAuthenticator(PANEL_PACKAGE, Set.of(PANEL_SIGNER)),
                probe);
    }

    private static RpcRequest probeRequest(int protocolVersion, RpcOperation operation) {
        return new RpcRequest(protocolVersion, "probe-request", operation, Map.of());
    }

    private static CallerEvidence validCaller() {
        return new CallerEvidence(10456, List.of(PANEL_PACKAGE), Set.of(PANEL_SIGNER));
    }

    private static CallerIdentity callerIdentity(CallerEvidence evidence) {
        return new CallerIdentity(evidence.uid(), 2000);
    }

    private static RuntimeProbeSnapshot readySnapshot() {
        return new RuntimeProbeSnapshot(true, true, 3, true, 2, true);
    }

    private static final class CountingProbe implements RuntimeProbeSource {
        private int calls;

        @Override
        public ServiceResult<RuntimeProbeSnapshot> read() {
            calls++;
            return ServiceResult.success(readySnapshot());
        }
    }
}
