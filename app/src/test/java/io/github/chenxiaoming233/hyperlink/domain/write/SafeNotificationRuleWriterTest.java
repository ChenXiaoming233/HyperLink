package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class SafeNotificationRuleWriterTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void createsAllEligibleNotificationsRuleWithLimitationCopy() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        TargetSelection selection =
                fixture.flow.select(SOURCE, "demo-target-a").value().orElseThrow();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selection.confirmAuthorization());

        RuleCreationResult created = result.value().orElseThrow();
        assertEquals("demo-target-a", created.rule().targetDeviceId());
        assertEquals("active", created.rule().status());
        assertTrue(created.rule().dslJson().contains("\"mode\":\"allEligibleNotifications\""));
        assertFalse(created.rule().dslJson().contains("andGroups"));
        assertTrue(created.notes().stream().anyMatch(note -> note.contains("系统闸门")));
        assertTrue(created.notes().stream().anyMatch(note -> note.contains("目标能力限制")));
        assertEquals(3, fixture.gateway.currentRules().size());
    }

    @Test
    public void repeatedCreationUpsertsBySourceAndTargetWithoutDuplicates() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        NotificationRule untouchedMac = fixture.gateway.currentRules().get(1);
        NotificationRule untouchedPad = fixture.gateway.currentRules().get(2);
        ConfirmedTargetSelection selected =
                fixture.flow.select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();

        RuleCreationResult first =
                fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();
        RuleCreationResult second =
                fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();

        assertEquals(first.rule().ruleId(), second.rule().ruleId());
        assertEquals(3, fixture.gateway.currentRules().size());
        assertEquals(2, fixture.gateway.writeCount());
        assertEquals("demo-rule-1", fixture.gateway.currentRules().get(0).ruleId());
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-a"));
        assertEquals(3, uniqueRuleIdCount(fixture.gateway.currentRules()));
        assertSameRule(untouchedMac, fixture.gateway.currentRules().get(1));
        assertSameRule(untouchedPad, fixture.gateway.currentRules().get(2));
    }

    @Test
    public void lkgCapturesOriginalRulesFiltersStatusesAndPermissions() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> originalRules =
                new ArrayList<>(fixture.gateway.currentRules());
        String originalSerialized = fixture.gateway.serializedJson();
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        RuleLkg lkg =
                fixture.writer.createAllNotificationsRule(selected)
                        .value()
                        .orElseThrow()
                        .lkg();

        assertSameRules(originalRules, lkg.rules());
        assertEquals(List.of("demo-rule-1", "demo-rule-2", "demo-rule-3"), lkg.orderedRuleIds());
        assertEquals(originalRules.get(0).dslJson(), lkg.filtersByRuleId().get("demo-rule-1"));
        assertEquals("paused", lkg.statusesByRuleId().get("demo-rule-2"));
        assertEquals(Boolean.TRUE, lkg.targetAuthorizations().get("demo-target-a"));
        assertEquals(Boolean.FALSE, lkg.targetAuthorizations().get("demo-target-c"));
        assertEquals(
                io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState.GRANTED,
                lkg.notificationAccess());
        assertEquals(originalSerialized, lkg.serializedJson());
    }

    @Test
    public void createsForOfflineTargetAndKeepsWarning() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected =
                fixture.flow.select(SOURCE, "demo-target-b")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();

        RuleCreationResult created =
                fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();

        assertEquals("demo-target-b", created.rule().targetDeviceId());
        assertTrue(created.notes().stream().anyMatch(note -> note.contains("不会排队")));
        assertTrue(created.notes().stream().anyMatch(note -> note.contains("不会自动重试")));
    }

    @Test
    public void firstAuthorizationConfirmationAllowsUnauthorizedTargetRuleCreation() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        TargetSelection unconfirmed =
                fixture.flow.select(SOURCE, "demo-target-c").value().orElseThrow();
        assertTrue(unconfirmed.requiresTargetAuthorization());
        assertEquals(0, fixture.gateway.writeCount());

        RuleCreationResult created =
                fixture.writer.createAllNotificationsRule(unconfirmed.confirmAuthorization())
                        .value()
                        .orElseThrow();

        assertEquals("demo-target-c", created.rule().targetDeviceId());
        assertTrue(created.notes().stream().anyMatch(note -> note.contains("目标尚未授权")));
    }

    @Test
    public void unknownVersionIsCreatedWithUnverifiedCompatibilityNotice() {
        Fixture fixture =
                fixture(
                        "demo-version-unknown",
                        "demo-version-tested",
                        false,
                        false);
        ConfirmedTargetSelection selected =
                fixture.flow.select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();

        RuleCreationResult created =
                fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();

        assertTrue(created.compatibilityUnverified());
        assertTrue(
                created.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    @Test
    public void unknownFieldsMakeTheWholeWriterReadOnlyWithoutWriting() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", true, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertEquals(0, fixture.lkgStore.saveCount());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(fixture.writer.readOnlyFaultMessage().contains("未知字段"));
        assertFalse(fixture.writer.createAllNotificationsRule(selected).isSuccess());
    }

    @Test
    public void unexpressibleDslMakesTheWholeWriterReadOnlyWithoutWriting() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, true);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(fixture.writer.readOnlyFaultMessage().contains("DSL"));
    }

    @Test
    public void cannotCreateWhenCapabilityProbeFails() {
        CapabilityEvidence incomplete =
                CapabilityEvidence.demoComplete()
                        .without(CapabilityKind.METHOD, "rule.store.write");
        Fixture fixture =
                fixture(
                        new RuleStoreSnapshot(
                                demoFlow(),
                                SOURCE,
                                incomplete,
                                false,
                                false));
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertTrue(result.userMessage().contains("能力"));
    }

    @Test
    public void snapshotReadFailureDoesNotWriteOrSaveLkg() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.failNextRead();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertEquals(0, fixture.lkgStore.saveCount());
    }

    @Test
    public void lkgSaveFailureStopsBeforeWrite() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.lkgStore.failNextSave();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertTrue(result.userMessage().contains("LKG"));
    }

    @Test
    public void postWriteReadFailureRollsBackFromOriginalLkgAndVerifies() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.failReadsAfterNextWrite(1);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("回滚"));
        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(2, fixture.gateway.writeCount());
        assertEquals(original.size(), fixture.gateway.currentRules().size());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void writeCallFailureAlsoRollsBackAndVerifiesOriginalRules() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.failNextWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("回滚"));
        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(2, fixture.gateway.writeCount());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void validationFailureRollsBackAndVerifiesEveryRule() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.corruptNextWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(2, fixture.gateway.writeCount());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void unverifiableRollbackLeavesWriterInReadOnlyFaultState() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.corruptNextWrite();
        fixture.gateway.failAllReadsAfterWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(fixture.writer.readOnlyFaultMessage().contains("无法验证回滚"));
    }

    @Test
    public void unknownFieldsAfterWriteTriggerRollbackAndVerification() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.markUnknownFieldsOnNextWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertFalse(fixture.writer.isReadOnlyFault());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void unexpressibleDslAfterWriteTriggersRollbackAndVerification() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.markUnexpressibleDslOnNextWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertFalse(fixture.writer.isReadOnlyFault());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void sharedReadOnlyFaultBlocksAnotherWriterForTheSameGateway() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        SafeNotificationRuleWriter otherWriter =
                new SafeNotificationRuleWriter(
                        fixture.gateway, new FakeLastKnownGoodStore());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.markUnknownFieldsOnEveryWrite();

        assertFalse(fixture.writer.createAllNotificationsRule(selected).isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());
        int writesBeforeSecondAttempt = fixture.gateway.writeCount();

        ServiceResult<RuleCreationResult> secondResult =
                otherWriter.createAllNotificationsRule(selected);

        assertFalse(secondResult.isSuccess());
        assertTrue(secondResult.userMessage().contains("保持只读"));
        assertEquals(writesBeforeSecondAttempt, fixture.gateway.writeCount());
    }

    @Test
    public void readBackExceptionRollsBackAndVerifiesOriginalRules() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.throwReadsAfterNextWrite(1);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertFalse(fixture.writer.isReadOnlyFault());
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void exceptionDuringRollbackLeavesSharedReadOnlyFault() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.throwEveryWrite();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(fixture.writer.readOnlyFaultMessage().contains("无法验证回滚"));
    }

    @Test
    public void targetStateChangeAfterSelectionStopsBeforeWrite() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        NotificationFlowSnapshot changedFlow = replaceTarget(demoFlow(), offlineTargetA());
        fixture.gateway.replaceSnapshot(
                new RuleStoreSnapshot(
                        changedFlow,
                        SOURCE,
                        CapabilityEvidence.demoComplete(),
                        false,
                        false));

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("目标状态"));
        assertEquals(0, fixture.gateway.writeCount());
    }

    @Test
    public void concurrentWritesAreSerializedWithoutOverwritingEachOther()
            throws Exception {
        NotificationFlowSnapshot flow =
                withTargets(
                        demoFlow(),
                        new TargetDevice(
                                "demo-target-second",
                                "Demo-Second",
                                DeviceType.PC,
                                true,
                                true,
                                true,
                                true),
                        new TargetDevice(
                                "demo-target-third",
                                "Demo-Third",
                                DeviceType.MAC,
                                true,
                                true,
                                true,
                                true));
        Fixture fixture =
                fixture(
                        new RuleStoreSnapshot(
                                flow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        fixture.gateway.setWriteDelayMillis(75L);
        ConfirmedTargetSelection second =
                confirmed(fixture, "demo-target-second");
        ConfirmedTargetSelection third =
                confirmed(fixture, "demo-target-third");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ServiceResult<RuleCreationResult>> firstResult =
                    executor.submit(
                            () -> fixture.writer.createAllNotificationsRule(second));
            Future<ServiceResult<RuleCreationResult>> secondResult =
                    executor.submit(
                            () -> fixture.writer.createAllNotificationsRule(third));

            assertTrue(firstResult.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(5, fixture.gateway.currentRules().size());
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-second"));
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-third"));
        assertEquals(5, uniqueRuleIdCount(fixture.gateway.currentRules()));
    }

    @Test
    public void concurrentRequestsAcrossWriterInstancesUseTheSameGatewayLock()
            throws Exception {
        NotificationFlowSnapshot flow =
                withTargets(
                        demoFlow(),
                        new TargetDevice(
                                "demo-target-second",
                                "Demo-Second",
                                DeviceType.PC,
                                true,
                                true,
                                true,
                                true),
                        new TargetDevice(
                                "demo-target-third",
                                "Demo-Third",
                                DeviceType.MAC,
                                true,
                                true,
                                true,
                                true));
        Fixture fixture =
                fixture(
                        new RuleStoreSnapshot(
                                flow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        SafeNotificationRuleWriter otherWriter =
                new SafeNotificationRuleWriter(
                        fixture.gateway, new FakeLastKnownGoodStore());
        fixture.gateway.setWriteDelayMillis(75L);
        ConfirmedTargetSelection second = confirmed(fixture, "demo-target-second");
        ConfirmedTargetSelection third = confirmed(fixture, "demo-target-third");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ServiceResult<RuleCreationResult>> firstResult =
                    executor.submit(
                            () -> fixture.writer.createAllNotificationsRule(second));
            Future<ServiceResult<RuleCreationResult>> secondResult =
                    executor.submit(() -> otherWriter.createAllNotificationsRule(third));

            assertTrue(firstResult.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(5, fixture.gateway.currentRules().size());
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-second"));
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-third"));
        assertTrue(fixture.writer.lastKnownGood() != null);
    }

    private static Fixture fixture(
            String version, String testedVersion, boolean unknownFields, boolean badDsl) {
        return fixture(
                new RuleStoreSnapshot(
                        demoFlow(),
                        SOURCE,
                        CapabilityEvidence.demoComplete(version, Set.of(testedVersion)),
                        unknownFields,
                        badDsl));
    }

    private static Fixture fixture(RuleStoreSnapshot snapshot) {
        FakeRuleStoreGateway gateway = new FakeRuleStoreGateway(snapshot);
        FakeLastKnownGoodStore lkgStore = new FakeLastKnownGoodStore();
        SafeNotificationRuleWriter writer = new SafeNotificationRuleWriter(gateway, lkgStore);
        return new Fixture(
                gateway,
                lkgStore,
                writer,
                new TargetSelectionFlow(snapshot.flowSnapshot()));
    }

    private static ConfirmedTargetSelection confirmed(Fixture fixture, String targetId) {
        return fixture.flow.select(SOURCE, targetId)
                .value()
                .orElseThrow()
                .confirmAuthorization();
    }

    private static NotificationFlowSnapshot demoFlow() {
        return FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
    }

    private static NotificationFlowSnapshot withTargets(
            NotificationFlowSnapshot snapshot, TargetDevice... targets) {
        List<TargetDevice> all = new ArrayList<>(snapshot.targets());
        all.addAll(List.of(targets));
        return new NotificationFlowSnapshot(
                snapshot.connectionState(),
                snapshot.stateVersion() + 1L,
                snapshot.compatibilityUnverified(),
                all,
                snapshot.rules(),
                snapshot.notificationAccess(),
                snapshot.staleCache());
    }

    private static NotificationFlowSnapshot replaceTarget(
            NotificationFlowSnapshot snapshot, TargetDevice replacement) {
        List<TargetDevice> targets = new ArrayList<>();
        for (TargetDevice target : snapshot.targets()) {
            targets.add(
                    target.deviceId().equals(replacement.deviceId()) ? replacement : target);
        }
        return new NotificationFlowSnapshot(
                snapshot.connectionState(),
                snapshot.stateVersion() + 1L,
                snapshot.compatibilityUnverified(),
                targets,
                snapshot.rules(),
                snapshot.notificationAccess(),
                snapshot.staleCache());
    }

    private static TargetDevice offlineTargetA() {
        return new TargetDevice(
                "demo-target-a",
                "Demo-PC",
                DeviceType.PC,
                false,
                false,
                true,
                true);
    }

    private static int matchingRuleCount(List<NotificationRule> rules, String targetId) {
        int count = 0;
        for (NotificationRule rule : rules) {
            if (rule.sourceDeviceId().equals(SOURCE.deviceId())
                    && rule.targetDeviceId().equals(targetId)) {
                count++;
            }
        }
        return count;
    }

    private static int uniqueRuleIdCount(List<NotificationRule> rules) {
        return (int) rules.stream().map(NotificationRule::ruleId).distinct().count();
    }

    private static void assertSameRules(
            List<NotificationRule> expected, List<NotificationRule> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSameRule(expected.get(index), actual.get(index));
        }
    }

    private static void assertSameRule(NotificationRule left, NotificationRule right) {
        assertEquals(left.ruleId(), right.ruleId());
        assertEquals(left.sourceDeviceId(), right.sourceDeviceId());
        assertEquals(left.sourceDeviceName(), right.sourceDeviceName());
        assertEquals(left.targetDeviceId(), right.targetDeviceId());
        assertEquals(left.targetDeviceName(), right.targetDeviceName());
        assertEquals(left.status(), right.status());
        assertEquals(left.summary(), right.summary());
        assertEquals(left.dslJson(), right.dslJson());
    }

    private static final class Fixture {
        private final FakeRuleStoreGateway gateway;
        private final FakeLastKnownGoodStore lkgStore;
        private final SafeNotificationRuleWriter writer;
        private final TargetSelectionFlow flow;

        private Fixture(
                FakeRuleStoreGateway gateway,
                FakeLastKnownGoodStore lkgStore,
                SafeNotificationRuleWriter writer,
                TargetSelectionFlow flow) {
            this.gateway = gateway;
            this.lkgStore = lkgStore;
            this.writer = writer;
            this.flow = flow;
        }
    }
}
