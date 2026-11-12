package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import java.util.Objects;

/**
 * In-memory stand-in for the persistent state shared by all fake gateway instances.
 */
public final class FakeRuleStore {
    private RuleStoreSnapshot snapshot;
    private String memoryReadOnlyFaultMessage = "";
    private int readCount;
    private int writeCount;

    public FakeRuleStore(RuleStoreSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public synchronized RuleStoreSnapshot snapshot() {
        return snapshot;
    }

    public synchronized void replaceSnapshot(RuleStoreSnapshot nextSnapshot) {
        RuleStoreSnapshot replacement =
                Objects.requireNonNull(nextSnapshot, "nextSnapshot");
        String existingFault = readOnlyFaultMessage();
        snapshot =
                existingFault.isEmpty()
                                || existingFault.equals(replacement.readOnlyFaultMessage())
                        ? replacement
                        : replacement.withReadOnlyFault(existingFault);
    }

    public synchronized void enterReadOnlyFault(String message) {
        Objects.requireNonNull(message, "message");
        if (snapshot.readOnlyFaultMessage().isEmpty()) {
            snapshot = snapshot.withReadOnlyFault(message);
        }
    }

    public synchronized void enterMemoryReadOnlyFault(String message) {
        if (memoryReadOnlyFaultMessage.isEmpty()) {
            memoryReadOnlyFaultMessage = Objects.requireNonNull(message, "message");
        }
    }

    public synchronized String readOnlyFaultMessage() {
        String persistent = snapshot.readOnlyFaultMessage();
        return persistent.isEmpty() ? memoryReadOnlyFaultMessage : persistent;
    }

    public synchronized int nextReadCount() {
        readCount++;
        return readCount;
    }

    public synchronized int nextWriteCount() {
        writeCount++;
        return writeCount;
    }

    public synchronized int readCount() {
        return readCount;
    }

    public synchronized int writeCount() {
        return writeCount;
    }
}
