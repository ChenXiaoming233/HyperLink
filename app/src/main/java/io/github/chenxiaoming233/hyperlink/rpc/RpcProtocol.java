package io.github.chenxiaoming233.hyperlink.rpc;

public final class RpcProtocol {
    public static final int VERSION = 1;

    private final int currentVersion;

    public RpcProtocol(int currentVersion) {
        if (currentVersion <= 0) {
            throw new IllegalArgumentException("currentVersion must be positive");
        }
        this.currentVersion = currentVersion;
    }

    public boolean supports(int requestedVersion) {
        return requestedVersion == currentVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}
