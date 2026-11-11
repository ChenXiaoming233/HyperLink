package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleLkg;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteReceipt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

public final class FakeRuleStoreGateway implements RuleStoreGateway {
    private final FakeRuleStore store;
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
    private String nextAuthorizationTargetId;
    private Boolean nextAuthorizationValue;
    private String everyAuthorizationTargetId;
    private Boolean everyAuthorizationValue;
    private NotificationAccessState nextNotificationAccess;
    private NotificationAccessState everyNotificationAccess;
    private TargetDevice nextTargetReplacement;
    private String afterWriteAuthorizationTargetId;
    private Boolean afterWriteAuthorizationValue;
    private List<NotificationRule> rulesAfterNextWrite;
    private String readOnlyFaultAfterNextRestore;
    private boolean failNextReadOnlyFaultPersistence;
    private long writeDelayMillis;
    private int activeWrites;
    private int maxActiveWrites;
    private CountDownLatch writeStartedLatch;
    private CountDownLatch commitGate;

    public FakeRuleStoreGateway(RuleStoreSnapshot snapshot) {
        this(new FakeRuleStore(snapshot));
    }

    public FakeRuleStoreGateway(FakeRuleStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public FakeRuleStore store() {
        return store;
    }

    @Override
    public synchronized ServiceResult<RuleStoreSnapshot> read() {
        store.nextReadCount();
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
        return ServiceResult.success(store.snapshot());
    }

    @Override
    public ServiceResult<RuleWriteReceipt> write(
            List<NotificationRule> rules,
            long expectedStoreVersion,
            List<NotificationRule> expectedRules) {
        return executeWrite(
                expectedStoreVersion,
                expectedRules,
                current -> current.withRules(rules),
                false);
    }

    @Override
    public ServiceResult<RuleWriteReceipt> restoreRules(
            RuleLkg lkg,
            long expectedStoreVersion,
            List<NotificationRule> expectedRules) {
        return executeWrite(
                expectedStoreVersion,
                expectedRules,
                current ->
                        current.withRules(lkg.rules())
                                .withSafetyFlags(false, false),
                true);
    }

    @Override
    public synchronized String readOnlyFaultMessage() {
        return store.readOnlyFaultMessage();
    }

    @Override
    public synchronized void enterReadOnlyFault(String message) {
        Objects.requireNonNull(message, "message");
        if (failNextReadOnlyFaultPersistence) {
            failNextReadOnlyFaultPersistence = false;
            store.enterMemoryReadOnlyFault(message);
            throw new IllegalStateException("演示只读故障态持久化失败。");
        }
        store.enterReadOnlyFault(message);
    }

    private ServiceResult<RuleWriteReceipt> executeWrite(
            long expectedStoreVersion,
            List<NotificationRule> expectedRules,
            UnaryOperator<RuleStoreSnapshot> preparation,
            boolean restore) {
        beginWrite();
        try {
            signalWriteStarted();
            delay();
            awaitCommitGate();
            synchronized (this) {
                synchronized (store) {
                    store.nextWriteCount();
                    if (throwEveryWrite || throwNextWrite) {
                        throwNextWrite = false;
                        throw new IllegalStateException("演示规则写入异常。");
                    }
                    if (failNextWrite) {
                        failNextWrite = false;
                        return ServiceResult.failure("演示规则写入失败。");
                    }
                    RuleStoreSnapshot current = store.snapshot();
                    if (!store.readOnlyFaultMessage().isEmpty()) {
                        return ServiceResult.failure("演示 store 处于持久只读故障态。");
                    }
                    if (current.storeVersion() != expectedStoreVersion
                            || !sameRules(current.rules(), expectedRules)) {
                        return ServiceResult.failure(
                                "演示 store 版本或规则内容已变化，未执行写入。");
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

                    RuleStoreSnapshot candidate =
                            applyCorruption(preparation.apply(current));
                    boolean unknownFields =
                            candidate.unknownFieldsPresent() || unknownFieldsOnEveryWrite
                                    || unknownFieldsOnNextWrite;
                    boolean unexpressibleDsl =
                            candidate.unexpressibleDslPresent() || unexpressibleDslOnNextWrite;
                    unknownFieldsOnNextWrite = false;
                    unexpressibleDslOnNextWrite = false;
                    RuleStoreSnapshot stored =
                            applyTargetMutation(
                                    applyPermissionMutations(
                                            candidate.withSafetyFlags(
                                                    unknownFields, unexpressibleDsl)));
                    store.replaceSnapshot(stored);
                    RuleWriteReceipt receipt =
                            new RuleWriteReceipt(
                                    stored.serializedJson(), stored.storeVersion());
                    applyAfterWriteMutations(stored);
                    if (restore && readOnlyFaultAfterNextRestore != null) {
                        store.enterReadOnlyFault(readOnlyFaultAfterNextRestore);
                        readOnlyFaultAfterNextRestore = null;
                    }
                    return ServiceResult.success(receipt);
                }
            }
        } finally {
            finishWrite();
        }
    }

    private RuleStoreSnapshot applyPermissionMutations(RuleStoreSnapshot candidate) {
        Map<String, Boolean> authorizations = new LinkedHashMap<>();
        for (TargetDevice target : candidate.flowSnapshot().targets()) {
            authorizations.put(target.deviceId(), target.targetAuthorized());
        }
        String targetId =
                everyAuthorizationTargetId != null
                        ? everyAuthorizationTargetId
                        : nextAuthorizationTargetId;
        Boolean value =
                everyAuthorizationTargetId != null
                        ? everyAuthorizationValue
                        : nextAuthorizationValue;
        if (targetId != null) {
            authorizations.put(targetId, value);
        }
        NotificationAccessState access =
                everyNotificationAccess != null
                        ? everyNotificationAccess
                        : nextNotificationAccess != null
                                ? nextNotificationAccess
                                : candidate.flowSnapshot().notificationAccess();
        nextAuthorizationTargetId = null;
        nextAuthorizationValue = null;
        nextNotificationAccess = null;
        return candidate.withPermissions(authorizations, access);
    }

    private RuleStoreSnapshot applyCorruption(RuleStoreSnapshot candidate) {
        if (!corruptNextWrite || candidate.rules().isEmpty()) {
            return candidate;
        }
        corruptNextWrite = false;
        List<NotificationRule> corrupted = new ArrayList<>(candidate.rules());
        NotificationRule first = corrupted.get(0);
        corrupted.set(
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
        return candidate.withRules(corrupted);
    }

    private RuleStoreSnapshot applyTargetMutation(RuleStoreSnapshot candidate) {
        TargetDevice replacement = nextTargetReplacement;
        nextTargetReplacement = null;
        return replacement == null ? candidate : candidate.withTarget(replacement);
    }

    private void applyAfterWriteMutations(RuleStoreSnapshot stored) {
        RuleStoreSnapshot current = stored;
        if (rulesAfterNextWrite != null) {
            current = withRulesAtCurrentVersion(current, rulesAfterNextWrite);
            rulesAfterNextWrite = null;
        }
        if (afterWriteAuthorizationTargetId != null) {
            Map<String, Boolean> authorizations = new LinkedHashMap<>();
            for (TargetDevice target : current.flowSnapshot().targets()) {
                authorizations.put(
                        target.deviceId(),
                        target.deviceId().equals(afterWriteAuthorizationTargetId)
                                ? afterWriteAuthorizationValue
                                : target.targetAuthorized());
            }
            current =
                    current.withPermissions(
                            authorizations,
                            current.flowSnapshot().notificationAccess());
            afterWriteAuthorizationTargetId = null;
            afterWriteAuthorizationValue = null;
        }
        if (current != stored) {
            store.replaceSnapshot(current);
        }
    }

    private static RuleStoreSnapshot withRulesAtCurrentVersion(
            RuleStoreSnapshot snapshot, List<NotificationRule> rules) {
        NotificationFlowSnapshot flow = snapshot.flowSnapshot();
        NotificationFlowSnapshot changedFlow =
                new NotificationFlowSnapshot(
                        flow.connectionState(),
                        snapshot.storeVersion(),
                        flow.compatibilityUnverified(),
                        flow.targets(),
                        rules,
                        flow.notificationAccess(),
                        flow.staleCache());
        return new RuleStoreSnapshot(
                changedFlow,
                snapshot.sourceDevice(),
                snapshot.capabilities(),
                snapshot.unknownFieldsPresent(),
                snapshot.unexpressibleDslPresent());
    }

    private synchronized void beginWrite() {
        activeWrites++;
        maxActiveWrites = Math.max(maxActiveWrites, activeWrites);
    }

    private synchronized void finishWrite() {
        activeWrites--;
    }

    private void signalWriteStarted() {
        CountDownLatch latch;
        synchronized (this) {
            latch = writeStartedLatch;
            writeStartedLatch = null;
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    private void awaitCommitGate() {
        CountDownLatch gate;
        synchronized (this) {
            gate = commitGate;
            commitGate = null;
        }
        if (gate == null) {
            return;
        }
        try {
            gate.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("演示写入栅栏等待被中断。", exception);
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

    public synchronized void changeTargetAuthorizationOnNextWrite(
            String targetDeviceId, boolean authorized) {
        nextAuthorizationTargetId = Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        nextAuthorizationValue = authorized;
    }

    public synchronized void changeTargetAuthorizationOnEveryWrite(
            String targetDeviceId, boolean authorized) {
        everyAuthorizationTargetId = Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        everyAuthorizationValue = authorized;
    }

    public synchronized void changeNotificationAccessOnNextWrite(
            NotificationAccessState notificationAccess) {
        nextNotificationAccess =
                Objects.requireNonNull(notificationAccess, "notificationAccess");
    }

    public synchronized void changeNotificationAccessOnEveryWrite(
            NotificationAccessState notificationAccess) {
        everyNotificationAccess =
                Objects.requireNonNull(notificationAccess, "notificationAccess");
    }

    public synchronized void changeTargetOnNextWrite(TargetDevice replacement) {
        nextTargetReplacement = Objects.requireNonNull(replacement, "replacement");
    }

    public synchronized void changeTargetAuthorizationAfterNextWrite(
            String targetDeviceId, boolean authorized) {
        afterWriteAuthorizationTargetId =
                Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        afterWriteAuthorizationValue = authorized;
    }

    public synchronized void replaceRulesAfterNextWrite(List<NotificationRule> rules) {
        rulesAfterNextWrite =
                Collections.unmodifiableList(
                        new ArrayList<>(Objects.requireNonNull(rules, "rules")));
    }

    public synchronized void enterReadOnlyFaultAfterNextRestore(String message) {
        readOnlyFaultAfterNextRestore = Objects.requireNonNull(message, "message");
    }

    public synchronized void failNextReadOnlyFaultPersistence() {
        failNextReadOnlyFaultPersistence = true;
    }

    public synchronized void signalWriteStarted(CountDownLatch latch) {
        writeStartedLatch = Objects.requireNonNull(latch, "latch");
    }

    public synchronized void pauseBeforeNextCommit(CountDownLatch latch) {
        commitGate = Objects.requireNonNull(latch, "latch");
    }

    public synchronized void replaceSnapshot(RuleStoreSnapshot snapshot) {
        store.replaceSnapshot(snapshot);
    }

    public synchronized RuleStoreSnapshot snapshot() {
        return store.snapshot();
    }

    public synchronized void setWriteDelayMillis(long writeDelayMillis) {
        if (writeDelayMillis < 0L) {
            throw new IllegalArgumentException("writeDelayMillis must not be negative");
        }
        this.writeDelayMillis = writeDelayMillis;
    }

    public synchronized int readCount() {
        return store.readCount();
    }

    public synchronized int writeCount() {
        return store.writeCount();
    }

    public synchronized List<NotificationRule> currentRules() {
        return Collections.unmodifiableList(
                new ArrayList<>(store.snapshot().flowSnapshot().rules()));
    }

    public synchronized String serializedJson() {
        return store.snapshot().serializedJson();
    }

    public synchronized int maxConcurrentWrites() {
        return maxActiveWrites;
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

    private static boolean sameRules(
            List<NotificationRule> left, List<NotificationRule> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            NotificationRule first = left.get(index);
            NotificationRule second = right.get(index);
            if (!first.ruleId().equals(second.ruleId())
                    || !first.sourceDeviceId().equals(second.sourceDeviceId())
                    || !first.sourceDeviceName().equals(second.sourceDeviceName())
                    || !first.targetDeviceId().equals(second.targetDeviceId())
                    || !first.targetDeviceName().equals(second.targetDeviceName())
                    || !first.status().equals(second.status())
                    || !first.summary().equals(second.summary())
                    || !first.dslJson().equals(second.dslJson())) {
                return false;
            }
        }
        return true;
    }
}
