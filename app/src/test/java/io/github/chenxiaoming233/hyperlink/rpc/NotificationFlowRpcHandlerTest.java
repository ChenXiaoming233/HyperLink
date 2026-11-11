package io.github.chenxiaoming233.hyperlink.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class NotificationFlowRpcHandlerTest {
    private static final String PANEL_PACKAGE = "io.github.chenxiaoming233.hyperlink";
    private static final String PANEL_SIGNER = "Aa11Bb22Cc33";

    @Test
    public void acceptsMatchingProtocolAndAuthenticatedCaller() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        CallerEvidence caller = validCaller();
        NotificationFlowRpcHandler handler = newHandler(service, caller);

        RpcResponse response =
                handler.handle(readRequest(RpcProtocol.VERSION), callerIdentity(caller));

        assertTrue(response.success());
        assertEquals(RpcErrorCode.NONE, response.errorCode());
        assertEquals(1L, response.stateVersion());
        assertEquals("CONNECTED", response.data().get("connectionState"));
    }

    @Test
    public void rejectsVersionMismatchWithoutCallingService() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        CallerEvidence caller = validCaller();
        NotificationFlowRpcHandler handler = newHandler(service, caller);

        RpcResponse response =
                handler.handle(
                        readRequest(RpcProtocol.VERSION + 1), callerIdentity(caller));

        assertFalse(response.success());
        assertEquals(RpcErrorCode.PROTOCOL_MISMATCH, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, service.readCount());
    }

    @Test
    public void authenticationFailureDoesNotLeakDataOrCallService() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.COMPATIBILITY_UNVERIFIED);
        CallerEvidence attacker =
                new CallerEvidence(10456, List.of("com.example.attacker"), Set.of(PANEL_SIGNER));
        NotificationFlowRpcHandler handler = newHandler(service, attacker);

        RpcResponse response =
                handler.handle(
                        readRequest(RpcProtocol.VERSION), callerIdentity(attacker));

        assertFalse(response.success());
        assertEquals(RpcErrorCode.AUTHENTICATION_FAILED, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0L, response.stateVersion());
        assertEquals(0, service.readCount());
        assertFalse(response.userMessage().contains("COMPATIBILITY"));
    }

    @Test
    public void wrongSignerIsRejectedEvenWhenPackageMatches() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        CallerEvidence attacker =
                new CallerEvidence(10456, List.of(PANEL_PACKAGE), Set.of("wrong-signer"));
        NotificationFlowRpcHandler handler = newHandler(service, attacker);

        RpcResponse response =
                handler.handle(
                        readRequest(RpcProtocol.VERSION), callerIdentity(attacker));

        assertFalse(response.success());
        assertEquals(RpcErrorCode.AUTHENTICATION_FAILED, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, service.readCount());
    }

    @Test
    public void unauthenticatedCallerCannotProbeProtocolVersion() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        CallerEvidence attacker =
                new CallerEvidence(10456, List.of("com.example.attacker"), Set.of(PANEL_SIGNER));
        NotificationFlowRpcHandler handler = newHandler(service, attacker);

        RpcResponse response =
                handler.handle(
                        readRequest(RpcProtocol.VERSION + 1), callerIdentity(attacker));

        assertEquals(RpcErrorCode.AUTHENTICATION_FAILED, response.errorCode());
        assertTrue(response.data().isEmpty());
        assertEquals(0, service.readCount());
    }

    @Test
    public void evidenceForDifferentUidIsRejected() {
        FakeNotificationFlowService service =
                new FakeNotificationFlowService(ConnectionState.CONNECTED);
        CallerEvidence caller = validCaller();
        NotificationFlowRpcHandler handler =
                new NotificationFlowRpcHandler(
                        new RpcProtocol(RpcProtocol.VERSION),
                        identity -> caller,
                        new PinnedCallerAuthenticator(PANEL_PACKAGE, Set.of(PANEL_SIGNER)),
                        service);

        RpcResponse response =
                handler.handle(
                        readRequest(RpcProtocol.VERSION),
                        new CallerIdentity(20456, 2001));

        assertEquals(RpcErrorCode.AUTHENTICATION_FAILED, response.errorCode());
        assertEquals(0, service.readCount());
    }

    private static NotificationFlowRpcHandler newHandler(
            NotificationFlowService service, CallerEvidence evidence) {
        CallerAuthenticator authenticator =
                new PinnedCallerAuthenticator(PANEL_PACKAGE, Set.of(PANEL_SIGNER));
        return new NotificationFlowRpcHandler(
                new RpcProtocol(RpcProtocol.VERSION),
                identity -> evidence,
                authenticator,
                service);
    }

    private static RpcRequest readRequest(int protocolVersion) {
        return new RpcRequest(protocolVersion, "request-1", RpcOperation.READ_SNAPSHOT, Map.of());
    }

    private static CallerEvidence validCaller() {
        return new CallerEvidence(10456, List.of(PANEL_PACKAGE), Set.of(PANEL_SIGNER));
    }

    private static CallerIdentity callerIdentity(CallerEvidence evidence) {
        return new CallerIdentity(evidence.uid(), 2000);
    }
}
