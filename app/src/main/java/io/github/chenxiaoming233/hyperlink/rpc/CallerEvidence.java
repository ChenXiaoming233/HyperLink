package io.github.chenxiaoming233.hyperlink.rpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CallerEvidence {
    private final int uid;
    private final List<String> packageNames;
    private final Set<String> signerSha256;

    public CallerEvidence(int uid, List<String> packageNames, Set<String> signerSha256) {
        if (uid <= 0) {
            throw new IllegalArgumentException("uid must be positive");
        }
        this.uid = uid;
        this.packageNames =
                Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(packageNames)));
        this.signerSha256 =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(Objects.requireNonNull(signerSha256)));
    }

    public int uid() {
        return uid;
    }

    public List<String> packageNames() {
        return packageNames;
    }

    public Set<String> signerSha256() {
        return signerSha256;
    }
}
