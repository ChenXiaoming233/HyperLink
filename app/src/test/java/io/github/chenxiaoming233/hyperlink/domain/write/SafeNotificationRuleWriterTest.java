package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    public void actualUnknownDslFieldsAreRejectedEvenWhenGatewayFlagsAreFalse() {
        List<String> invalidDsl =
                List.of(
                        """
                        {"ruleText":"演示规则","unknownField":true}
                        """,
                        """
                        {"andGroups":[{"branches":[[{"field":"app","op":"contains","value":"demo.app"}]}]],"ruleText":"演示规则"}
                        """,
                        """
                        {"andGroups":[{"branches":[[{"field":"unknownField","op":"is","value":"true"}]]}],"ruleText":"演示规则"}
                        """,
                        """
                        {"andGroups":[{"branches":"not-an-array"}],"ruleText":"演示规则"}
                        """);

        for (String dsl : invalidDsl) {
            Fixture fixture = fixture(snapshotWithRule(demoFlow(), "active", dsl));
            ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

            ServiceResult<RuleCreationResult> result =
                    fixture.writer.createAllNotificationsRule(selected);

            assertFalse(result.isSuccess());
            assertEquals(0, fixture.gateway.writeCount());
            assertTrue(fixture.writer.isReadOnlyFault());
        }
    }

    @Test
    public void unknownStoredStatusIsRejectedEvenWhenGatewayFlagsAreFalse() {
        Fixture fixture =
                fixture(
                        snapshotWithRule(
                                demoFlow(),
                                "blocked",
                                "{\"ruleText\":\"演示规则\"}"));
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertTrue(fixture.writer.isReadOnlyFault());
    }

    @Test
    public void deeplyNestedDslFailsClosedWithoutEscapingAsError() {
        StringBuilder dsl = new StringBuilder("{\"andGroups\":");
        for (int index = 0; index < 20_000; index++) {
            dsl.append('[');
        }
        dsl.append('0');
        for (int index = 0; index < 20_000; index++) {
            dsl.append(']');
        }
        dsl.append(",\"ruleText\":\"演示规则\"}");
        Fixture fixture =
                fixture(snapshotWithRule(demoFlow(), "active", dsl.toString()));
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertEquals(0, fixture.gateway.writeCount());
        assertTrue(fixture.writer.isReadOnlyFault());
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
    public void lkgSaveExceptionStopsBeforeWrite() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.lkgStore.failNextSaveWithException();

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
    public void targetAuthorizationChangeDuringWriteRollsBackAndVerifiesPermissions() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.changeTargetAuthorizationOnNextWrite("demo-target-a", false);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("回滚"));
        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(2, fixture.gateway.writeCount());
        assertTrue(targetAuthorized(fixture.gateway.snapshot(), "demo-target-a"));
    }

    @Test
    public void notificationAccessChangeDuringWriteRollsBackAndVerifiesPermissions() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.changeNotificationAccessOnNextWrite(NotificationAccessState.MISSING);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("回滚"));
        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(
                NotificationAccessState.GRANTED,
                fixture.gateway.snapshot().flowSnapshot().notificationAccess());
    }

    @Test
    public void unverifiableNotificationAccessRollbackLeavesPersistentFault() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.changeNotificationAccessOnEveryWrite(NotificationAccessState.MISSING);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(result.userMessage().contains("无法验证回滚"));

        int writesAfterFault = fixture.gateway.writeCount();
        FakeRuleStoreGateway newGateway = new FakeRuleStoreGateway(fixture.gateway.store());
        SafeNotificationRuleWriter newWriter =
                new SafeNotificationRuleWriter(newGateway, new FakeLastKnownGoodStore());

        assertFalse(newWriter.createAllNotificationsRule(selected).isSuccess());
        assertTrue(newWriter.isReadOnlyFault());
        assertEquals(writesAfterFault, newGateway.writeCount());
    }

    @Test
    public void concurrentPermissionChangeDuringWriteFailsInsteadOfBeingOverwritten() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch commitGate = new CountDownLatch(1);
        fixture.gateway.signalWriteStarted(writeStarted);
        fixture.gateway.pauseBeforeNextCommit(commitGate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ServiceResult<RuleCreationResult>> future =
                    executor.submit(
                            () -> fixture.writer.createAllNotificationsRule(selected));
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
            FakeRuleStoreGateway externalGateway =
                    new FakeRuleStoreGateway(fixture.gateway.store());
            externalGateway.replaceSnapshot(
                    withTargetAuthorization(
                            externalGateway.snapshot(), "demo-target-a", false));
            commitGate.countDown();

            assertFalse(future.get(5, TimeUnit.SECONDS).isSuccess());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        } finally {
            commitGate.countDown();
            executor.shutdownNow();
        }

        assertFalse(fixture.writer.isReadOnlyFault());
        assertEquals(2, fixture.gateway.writeCount());
        assertTrue(targetAuthorized(fixture.gateway.snapshot(), "demo-target-a"));
    }

    @Test
    public void persistentFaultDuringWriteIsNotOverwrittenByOlderSnapshot() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch commitGate = new CountDownLatch(1);
        fixture.gateway.signalWriteStarted(writeStarted);
        fixture.gateway.pauseBeforeNextCommit(commitGate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ServiceResult<RuleCreationResult>> future =
                    executor.submit(
                            () -> fixture.writer.createAllNotificationsRule(selected));
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
            FakeRuleStoreGateway externalGateway =
                    new FakeRuleStoreGateway(fixture.gateway.store());
            externalGateway.enterReadOnlyFault("演示持久只读故障。");
            commitGate.countDown();

            assertFalse(future.get(5, TimeUnit.SECONDS).isSuccess());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        } finally {
            commitGate.countDown();
            executor.shutdownNow();
        }

        assertTrue(fixture.writer.readOnlyFaultMessage().contains("演示持久只读故障"));
        assertSameRules(original, fixture.gateway.currentRules());
    }

    @Test
    public void unverifiablePermissionRollbackLeavesPersistentReadOnlyFault() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        fixture.gateway.changeTargetAuthorizationOnEveryWrite("demo-target-a", false);

        ServiceResult<RuleCreationResult> result =
                fixture.writer.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(result.userMessage().contains("无法验证回滚"));

        int writesAfterFault = fixture.gateway.writeCount();
        FakeRuleStoreGateway newGateway = new FakeRuleStoreGateway(fixture.gateway.store());
        SafeNotificationRuleWriter newWriter =
                new SafeNotificationRuleWriter(newGateway, new FakeLastKnownGoodStore());

        ServiceResult<RuleCreationResult> newResult =
                newWriter.createAllNotificationsRule(selected);

        assertFalse(newResult.isSuccess());
        assertTrue(newWriter.isReadOnlyFault());
        assertEquals(writesAfterFault, newGateway.writeCount());
    }

    @Test
    public void explicitRestorePathDoesNotSilentlyClearPersistentFault() {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        RuleLkg originalLkg =
                fixture.writer.createAllNotificationsRule(selected)
                        .value()
                        .orElseThrow()
                        .lkg();
        fixture.gateway.markUnknownFieldsOnEveryWrite();
        assertFalse(fixture.writer.createAllNotificationsRule(selected).isSuccess());
        assertTrue(fixture.writer.isReadOnlyFault());

        FakeRuleStoreGateway newGateway = new FakeRuleStoreGateway(fixture.gateway.store());
        assertFalse(newGateway.restore(originalLkg).isSuccess());
        int writesAfterRestore = newGateway.writeCount();
        SafeNotificationRuleWriter newWriter =
                new SafeNotificationRuleWriter(newGateway, new FakeLastKnownGoodStore());

        ServiceResult<RuleCreationResult> result =
                newWriter.createAllNotificationsRule(selected);

        assertFalse(result.isSuccess());
        assertTrue(newWriter.isReadOnlyFault());
        assertEquals(writesAfterRestore, newGateway.writeCount());
    }

    @Test
    public void compatibilityFlagFromSnapshotIsCombinedWithProbeEvidence() {
        NotificationFlowSnapshot unverifiedFlow =
                demoFlow().withConnectionState(
                        ConnectionState.COMPATIBILITY_UNVERIFIED,
                        demoFlow().stateVersion() + 1L);
        Fixture fixture =
                fixture(
                        new RuleStoreSnapshot(
                                unverifiedFlow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

        RuleCreationResult created =
                fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();

        assertTrue(created.compatibilityUnverified());
        assertTrue(
                created.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    @Test
    public void missingOrRevokedNotificationAccessUsesSafeGateCopy() {
        for (NotificationAccessState access :
                List.of(NotificationAccessState.MISSING, NotificationAccessState.REVOKED)) {
            NotificationFlowSnapshot flow = demoFlow().withNotificationAccess(access);
            Fixture fixture =
                    fixture(
                            new RuleStoreSnapshot(
                                    flow,
                                    SOURCE,
                                    CapabilityEvidence.demoComplete(),
                                    false,
                                    false));
            ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");

            RuleCreationResult created =
                    fixture.writer.createAllNotificationsRule(selected).value().orElseThrow();

            String expected = access == NotificationAccessState.MISSING ? "缺失" : "已撤销";
            assertTrue(created.notes().stream().anyMatch(note -> note.contains(expected)));
            assertTrue(created.notes().stream().anyMatch(note -> note.contains("受阻")));
        }
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

    @Test
    public void concurrentUpsertsQueueAtOneWriteAtATime() throws Exception {
        Fixture fixture = fixture("demo-version-tested", "demo-version-tested", false, false);
        fixture.gateway.setWriteDelayMillis(20L);
        ConfirmedTargetSelection selected = confirmed(fixture, "demo-target-a");
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ServiceResult<RuleCreationResult>>> results = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return fixture.writer.createAllNotificationsRule(selected);
                                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<ServiceResult<RuleCreationResult>> result : results) {
                assertTrue(result.get(5, TimeUnit.SECONDS).isSuccess());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(requestCount, fixture.gateway.writeCount());
        assertEquals(1, fixture.gateway.maxConcurrentWrites());
        assertEquals(3, fixture.gateway.currentRules().size());
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), "demo-target-a"));
        assertEquals(3, uniqueRuleIdCount(fixture.gateway.currentRules()));
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

    private static RuleStoreSnapshot snapshotWithRule(
            NotificationFlowSnapshot snapshot, String status, String dslJson) {
        List<NotificationRule> rules = new ArrayList<>(snapshot.rules());
        NotificationRule first = rules.get(0);
        rules.set(
                0,
                new NotificationRule(
                        first.ruleId(),
                        first.sourceDeviceId(),
                        first.sourceDeviceName(),
                        first.targetDeviceId(),
                        first.targetDeviceName(),
                        status,
                        first.summary(),
                        dslJson));
        NotificationFlowSnapshot changed =
                new NotificationFlowSnapshot(
                        snapshot.connectionState(),
                        snapshot.stateVersion() + 1L,
                        snapshot.compatibilityUnverified(),
                        snapshot.targets(),
                        rules,
                        snapshot.notificationAccess(),
                        snapshot.staleCache());
        return new RuleStoreSnapshot(
                changed,
                SOURCE,
                CapabilityEvidence.demoComplete(),
                false,
                false);
    }

    private static boolean targetAuthorized(
            RuleStoreSnapshot snapshot, String targetDeviceId) {
        return snapshot.flowSnapshot().targets().stream()
                .filter(target -> target.deviceId().equals(targetDeviceId))
                .findFirst()
                .orElseThrow()
                .targetAuthorized();
    }

    private static RuleStoreSnapshot withTargetAuthorization(
            RuleStoreSnapshot snapshot, String targetDeviceId, boolean authorized) {
        Map<String, Boolean> authorizations = new LinkedHashMap<>();
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            authorizations.put(
                    target.deviceId(),
                    target.deviceId().equals(targetDeviceId)
                            ? authorized
                            : target.targetAuthorized());
        }
        return snapshot.withPermissions(
                authorizations, snapshot.flowSnapshot().notificationAccess());
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
