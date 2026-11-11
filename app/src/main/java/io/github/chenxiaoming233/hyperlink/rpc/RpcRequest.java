package io.github.chenxiaoming233.hyperlink.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RpcRequest {
    private final int protocolVersion;
    private final String requestId;
    private final RpcOperation operation;
    private final Map<String, Object> arguments;

    public RpcRequest(
            int protocolVersion,
            String requestId,
            RpcOperation operation,
            Map<String, Object> arguments) {
        this.protocolVersion = protocolVersion;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.arguments =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(arguments, "arguments")));
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String requestId() {
        return requestId;
    }

    public RpcOperation operation() {
        return operation;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }
}
