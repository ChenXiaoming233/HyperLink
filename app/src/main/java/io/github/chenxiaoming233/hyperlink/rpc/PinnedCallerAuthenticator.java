package io.github.chenxiaoming233.hyperlink.rpc;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PinnedCallerAuthenticator implements CallerAuthenticator {
    private final String expectedPackage;
    private final Set<String> trustedSignerSha256;

    public PinnedCallerAuthenticator(String expectedPackage, Set<String> trustedSignerSha256) {
        this.expectedPackage = Objects.requireNonNull(expectedPackage, "expectedPackage");
        this.trustedSignerSha256 =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                Objects.requireNonNull(
                                        trustedSignerSha256, "trustedSignerSha256")));
        if (this.trustedSignerSha256.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted signer is required");
        }
    }

    @Override
    public boolean isAuthenticated(CallerEvidence caller) {
        return caller != null
                && caller.uid() > 0
                && caller.packageNames().contains(expectedPackage)
                && !Collections.disjoint(caller.signerSha256(), trustedSignerSha256);
    }
}
