package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteReceipt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FakeRuleStoreGateway implements RuleStoreGateway {
    private RuleStoreSnapshot snapshot;
    private int readCount;
    private int writeCount;
    private int readsToFail;
    private boolean failAllReads;
    private boolean failNextWrite;
    private boolean corruptNextWrite;
    private boolean activateReadFailureAfterWrite;
    private int readsToFailAfterWrite;
    private boolean failAllReadsAfterWrite;
    private boolean activateThrowAfterWrite;
    private int throwsToActivateAfterWrite;
    private int readsToThrowAfterWrite;
    private boolean throwNextWrite;
    private boolean throwEveryWrite;
    private boolean unknownFieldsOnNextWrite;
    private boolean unknownFieldsOnEveryWrite;
    private boolean unexpressibleDslOnNextWrite;
    private long writeDelayMillis;

    public FakeRuleStoreGateway(RuleStoreSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public synchronized ServiceResult<RuleStoreSnapshot> read() {
        readCount++;
        if (readsToThrowAfterWrite > 0) {
            readsToThrowAfterWrite--;
            throw new IllegalStateException("演示规则读取异常。");
        }
        if (failAllReads || readsToFail > 0) {
            if (readsToFail > 0) {
                readsToFail--;
            }
            return ServiceResult.failure("演示规则读取失败。");
        }
        return ServiceResult.success(snapshot);
    }

    @Override
    public ServiceResult<RuleWriteReceipt> write(List<NotificationRule> rules) {
        delay();
        synchronized (this) {
            writeCount++;
            if (throwEveryWrite || throwNextWrite) {
                throwNextWrite = false;
                throw new IllegalStateException("演示规则写入异常。");
            }
            if (failNextWrite) {
                failNextWrite = false;
                return ServiceResult.failure("演示规则写入失败。");
            }
            if (activateReadFailureAfterWrite) {
                readsToFail += readsToFailAfterWrite;
                failAllReads = failAllReadsAfterWrite;
                activateReadFailureAfterWrite = false;
                readsToFailAfterWrite = 0;
                failAllReadsAfterWrite = false;
            }
            if (activateThrowAfterWrite) {
                readsToThrowAfterWrite += throwsToActivateAfterWrite;
                throwsToActivateAfterWrite = 0;
                activateThrowAfterWrite = false;
            }

            List<NotificationRule> stored = new ArrayList<>(rules);
            if (corruptNextWrite && !stored.isEmpty()) {
                corruptNextWrite = false;
                NotificationRule first = stored.get(0);
                stored.set(
                        0,
                        new NotificationRule(
                                first.ruleId(),
                                first.sourceDeviceId(),
                                first.sourceDeviceName(),
                                first.targetDeviceId(),
                                first.targetDeviceName(),
                                first.status(),
                                first.summary(),
                                first.dslJson() + " "));
            }
            boolean unknownFields =
                    unknownFieldsOnEveryWrite || unknownFieldsOnNextWrite;
            boolean unexpressibleDsl = unexpressibleDslOnNextWrite;
            unknownFieldsOnNextWrite = false;
            unexpressibleDslOnNextWrite = false;
            snapshot =
                    snapshot
                            .withRules(stored)
                            .withSafetyFlags(unknownFields, unexpressibleDsl);
            return ServiceResult.success(new RuleWriteReceipt(snapshot.serializedJson()));
        }
    }

    public synchronized void failNextRead() {
        readsToFail++;
    }

    public synchronized void failNextWrite() {
        failNextWrite = true;
    }

    public synchronized void corruptNextWrite() {
        corruptNextWrite = true;
    }

    public synchronized void failReadsAfterNextWrite(int count) {
        activateReadFailureAfterWrite = true;
        readsToFailAfterWrite = count;
        failAllReadsAfterWrite = false;
    }

    public synchronized void failAllReadsAfterWrite() {
        activateReadFailureAfterWrite = true;
        failAllReadsAfterWrite = true;
    }

    public synchronized void throwReadsAfterNextWrite(int count) {
        activateThrowAfterWrite = true;
        throwsToActivateAfterWrite = count;
    }

    public synchronized void throwEveryWrite() {
        throwEveryWrite = true;
    }

    public synchronized void markUnknownFieldsOnNextWrite() {
        unknownFieldsOnNextWrite = true;
    }

    public synchronized void markUnknownFieldsOnEveryWrite() {
        unknownFieldsOnEveryWrite = true;
    }

    public synchronized void markUnexpressibleDslOnNextWrite() {
        unexpressibleDslOnNextWrite = true;
    }

    public synchronized void replaceSnapshot(RuleStoreSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public synchronized void setWriteDelayMillis(long writeDelayMillis) {
        if (writeDelayMillis < 0L) {
            throw new IllegalArgumentException("writeDelayMillis must not be negative");
        }
        this.writeDelayMillis = writeDelayMillis;
    }

    public synchronized int readCount() {
        return readCount;
    }

    public synchronized int writeCount() {
        return writeCount;
    }

    public synchronized List<NotificationRule> currentRules() {
        return Collections.unmodifiableList(
                new ArrayList<>(snapshot.flowSnapshot().rules()));
    }

    public synchronized String serializedJson() {
        return snapshot.serializedJson();
    }

    private void delay() {
        if (writeDelayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(writeDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
