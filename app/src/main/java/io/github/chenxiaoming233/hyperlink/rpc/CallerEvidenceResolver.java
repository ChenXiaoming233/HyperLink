package io.github.chenxiaoming233.hyperlink.rpc;

public interface CallerEvidenceResolver {
    CallerEvidence resolve(CallerIdentity identity);
}
