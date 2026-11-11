package io.github.chenxiaoming233.hyperlink.rpc;

import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowService;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.Objects;

public final class NotificationFlowRpcHandler {
    private final RpcProtocol protocol;
    private final CallerEvidenceResolver evidenceResolver;
    private final CallerAuthenticator authenticator;
    private final NotificationFlowService service;

    public NotificationFlowRpcHandler(
            RpcProtocol protocol,
            CallerEvidenceResolver evidenceResolver,
            CallerAuthenticator authenticator,
            NotificationFlowService service) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.evidenceResolver =
                Objects.requireNonNull(evidenceResolver, "evidenceResolver");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.service = Objects.requireNonNull(service, "service");
    }

    public RpcResponse handle(RpcRequest request, CallerIdentity identity) {
        String requestId = request == null ? "" : request.requestId();
        if (identity == null) {
            return RpcResponse.failure(requestId, RpcErrorCode.AUTHENTICATION_FAILED);
        }

        CallerEvidence evidence;
        try {
            evidence = evidenceResolver.resolve(identity);
        } catch (RuntimeException exception) {
            evidence = null;
        }
        if (evidence == null
                || evidence.uid() != identity.uid()
                || !authenticator.isAuthenticated(evidence)) {
            return RpcResponse.failure(requestId, RpcErrorCode.AUTHENTICATION_FAILED);
        }

        if (request == null || request.operation() != RpcOperation.READ_SNAPSHOT) {
            return RpcResponse.failure(requestId, RpcErrorCode.INVALID_REQUEST);
        }
        if (!protocol.supports(request.protocolVersion())) {
            return RpcResponse.failure(requestId, RpcErrorCode.PROTOCOL_MISMATCH);
        }

        ServiceResult<NotificationFlowSnapshot> result = service.readSnapshot();
        if (!result.isSuccess()) {
            return RpcResponse.failure(requestId, RpcErrorCode.SERVICE_UNAVAILABLE);
        }
        return RpcResponse.success(requestId, result.value().get());
    }
}
