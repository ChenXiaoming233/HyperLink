package io.github.chenxiaoming233.hyperlink.rpc;

public interface CallerAuthenticator {
    boolean isAuthenticated(CallerEvidence caller);
}
