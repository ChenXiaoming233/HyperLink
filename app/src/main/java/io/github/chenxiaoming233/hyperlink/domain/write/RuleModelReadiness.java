package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.Objects;

/**
 * Read-only preflight for the complete rule model. Re-run {@link #inspect(RuleStoreGateway)}
 * after the fake snapshot changes to expose a new detection result to the UI.
 */
public final class RuleModelReadiness {
    private final boolean writable;
    private final String reason;

    private RuleModelReadiness(boolean writable, String reason) {
        this.writable = writable;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public static RuleModelReadiness inspect(RuleStoreGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        ServiceResult<RuleStoreSnapshot> readResult;
        try {
            readResult = gateway.read();
        } catch (RuntimeException exception) {
            return readOnly("无法读取完整规则快照，整份规则模型保持只读。");
        }
        if (!readResult.isSuccess()) {
            return readOnly("无法读取完整规则快照，整份规则模型保持只读。");
        }
        RuleStoreSnapshot snapshot = readResult.value().orElseThrow();
        String persistentFault;
        try {
            persistentFault = gateway.readOnlyFaultMessage();
        } catch (RuntimeException exception) {
            persistentFault = "无法读取持久只读故障态。";
        }
        if (persistentFault != null && !persistentFault.trim().isEmpty()) {
            return readOnly(persistentFault);
        }
        if (!snapshot.readOnlyFaultMessage().isEmpty()) {
            return readOnly(snapshot.readOnlyFaultMessage());
        }
        CapabilityReport capability = CapabilityProbe.evaluate(snapshot.capabilities());
        if (!capability.passed()) {
            return readOnly(capability.readOnlyMessage());
        }
        String issue = RuleSnapshotValidator.issue(snapshot);
        return issue.isEmpty() ? writableResult() : readOnly(issue);
    }

    public boolean writable() {
        return writable;
    }

    public String reason() {
        return reason;
    }

    private static RuleModelReadiness writableResult() {
        return new RuleModelReadiness(true, "");
    }

    private static RuleModelReadiness readOnly(String reason) {
        return new RuleModelReadiness(false, reason);
    }
}
