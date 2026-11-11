package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.fake.FakeTargetPermissionGateway;
import io.github.chenxiaoming233.hyperlink.ui.PanelDisplayModel;
import io.github.chenxiaoming233.hyperlink.ui.PanelStateMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class RuleLifecycleControllerTest {
    private static final long UNDO_WINDOW_MILLIS = 30_000L;

    @Test
    public void pausePersistsOnlyStatusAndImmediatelyUpdatesModelWithUndo() {
        Fixture fixture = fixture();
        NotificationRule before = fixture.rule("demo-rule-1");

        ServiceResult<RuleLifecycleModel> result = fixture.controller.pause("demo-rule-1");

        assertTrue(result.isSuccess());
        NotificationRule paused = fixture.rule("demo-rule-1");
        assertEquals("paused", paused.status());
        assertEquals(before.ruleId(), paused.ruleId());
        assertEquals(before.dslJson(), paused.dslJson());
        assertEquals(before.targetDeviceId(), paused.targetDeviceId());
        assertEquals(before.createdAt(), paused.createdAt());
        assertEquals(1, fixture.gateway.writeCount());
        assertNotNull(fixture.writer.lastKnownGood());
        assertEquals(
                "active",
                fixture.writer.lastKnownGood().statusesByRuleId().get("demo-rule-1"));
        assertTrue(fixture.target("demo-target-a").targetAuthorized());
        RuleLifecycleModel model = result.value().orElseThrow();
        assertTrue(model.rule("demo-rule-1").orElseThrow().canResume());
        assertTrue(model.undoToken().isPresent());
        assertEquals(0, fixture.permissions.revokeCount());
    }

    @Test
    public void resumeReusesRuleIdAndPreservesFilterMetadata() {
        Fixture fixture = fixture();
        NotificationRule before = fixture.rule("demo-rule-2");

        ServiceResult<RuleLifecycleModel> result = fixture.controller.resume("demo-rule-2");

        assertTrue(result.isSuccess());
        NotificationRule resumed = fixture.rule("demo-rule-2");
        assertEquals("active", resumed.status());
        assertEquals(before.ruleId(), resumed.ruleId());
        assertEquals(before.dslJson(), resumed.dslJson());
        assertEquals(before.summary(), resumed.summary());
        assertEquals(before.createdAt(), resumed.createdAt());
        assertEquals(3, fixture.gateway.currentRules().size());
        assertTrue(result.value().orElseThrow().rule("demo-rule-2").orElseThrow().canPause());
    }

    @Test
    public void undoWithinWindowRestoresStatusAndExpiredUndoFails() {
        Fixture fixture = fixture();

        RuleLifecycleModel paused =
                fixture.controller.pause("demo-rule-1").value().orElseThrow();
        RuleUndoToken firstToken = paused.undoToken().orElseThrow();
        ServiceResult<RuleLifecycleModel> undone = fixture.controller.undo(firstToken);

        assertTrue(undone.isSuccess());
        assertEquals("active", fixture.rule("demo-rule-1").status());

        RuleLifecycleModel pausedAgain =
                fixture.controller.pause("demo-rule-1").value().orElseThrow();
        RuleUndoToken secondToken = pausedAgain.undoToken().orElseThrow();
        assertTrue(secondToken.sequence() > firstToken.sequence());
        fixture.clock.addAndGet(UNDO_WINDOW_MILLIS + 1L);

        ServiceResult<RuleLifecycleModel> expired = fixture.controller.undo(secondToken);

        assertFalse(expired.isSuccess());
        assertTrue(expired.userMessage().contains("过期"));
        assertEquals("paused", fixture.rule("demo-rule-1").status());
    }

    @Test
    public void resumeUndoRestoresPausedStatus() {
        Fixture fixture = fixture();

        RuleLifecycleModel resumed =
                fixture.controller.resume("demo-rule-2").value().orElseThrow();
        assertTrue(fixture.controller.undo(resumed.undoToken().orElseThrow()).isSuccess());

        assertEquals("paused", fixture.rule("demo-rule-2").status());
    }

    @Test
    public void failedUndoKeepsTokenAndRetriesAfterStaleSnapshotRecovers() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.setStaleCache(true);

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertUndoTokenRetained(fixture, token);

        fixture.setStaleCache(false);
        assertTrue(fixture.controller.refresh().value().orElseThrow().canUndo());
        assertTrue(fixture.controller.undo(token).isSuccess());
        assertEquals("active", fixture.rule("demo-rule-1").status());
    }

    @Test
    public void failedUndoKeepsTokenAndRetriesAfterTargetAuthorizationRecovers() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.setTargetAuthorization("demo-target-a", false);

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertUndoTokenRetained(fixture, token);

        fixture.setTargetAuthorization("demo-target-a", true);
        assertTrue(fixture.controller.refresh().value().orElseThrow().canUndo());
        assertTrue(fixture.controller.undo(token).isSuccess());
        assertEquals("active", fixture.rule("demo-rule-1").status());
    }

    @Test
    public void failedUndoKeepsTokenAndRetriesAfterRuleTargetIsRestored() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.changeRuleTarget("demo-rule-1", "demo-target-g");

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertUndoTokenRetained(fixture, token);

        fixture.changeRuleTarget("demo-rule-1", "demo-target-a");
        assertTrue(fixture.controller.refresh().value().orElseThrow().canUndo());
        assertTrue(fixture.controller.undo(token).isSuccess());
        assertEquals("active", fixture.rule("demo-rule-1").status());
    }

    @Test
    public void failedUndoKeepsTokenWhenPersistentReadOnlyFaultBlocksWrites() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.gateway.enterReadOnlyFault("演示持久只读故障。");
        int writesBefore = fixture.gateway.writeCount();

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertUndoTokenRetained(fixture, token);
        assertFalse(fixture.controller.model().canUndo());
        assertEquals(writesBefore, fixture.gateway.writeCount());
        assertEquals("paused", fixture.rule("demo-rule-1").status());
    }

    @Test
    public void deleteRequiresOneExplicitConfirmationThatExplainsAuthorizationRevocation() {
        Fixture fixture = fixture();
        int writesBefore = fixture.gateway.writeCount();

        ServiceResult<DeleteConfirmation> requested =
                fixture.controller.requestDelete("demo-rule-1");
        assertTrue(requested.isSuccess());
        DeleteConfirmation confirmation = requested.value().orElseThrow();
        assertTrue(confirmation.prompt().contains("Demo-PC"));
        assertTrue(confirmation.prompt().contains("撤销"));
        assertTrue(confirmation.prompt().contains("目标授权"));

        ServiceResult<RuleLifecycleModel> cancelled =
                fixture.controller.confirmDelete(confirmation, false);
        assertTrue(cancelled.isSuccess());
        assertEquals(writesBefore, fixture.gateway.writeCount());
        assertNotNull(fixture.rule("demo-rule-1"));
        assertEquals(0, fixture.permissions.revokeCount());

        ServiceResult<DeleteConfirmation> secondRequest =
                fixture.controller.requestDelete("demo-rule-1");
        ServiceResult<RuleLifecycleModel> accepted =
                fixture.controller.confirmDelete(
                        secondRequest.value().orElseThrow(), true);
        assertTrue(accepted.isSuccess());
        assertFalse(fixture.hasRule("demo-rule-1"));
        assertEquals(1, fixture.permissions.revokeCount());
        assertFalse(fixture.target("demo-target-a").targetAuthorized());
        assertFalse(fixture.controller.confirmDelete(confirmation, true).isSuccess());
    }

    @Test
    public void permissionRevocationFailureEntersBlockedFaultWithoutFakeSuccess() {
        Fixture fixture = fixture();
        fixture.permissions.failNextRevoke("演示目标授权撤销失败。");
        DeleteConfirmation confirmation =
                fixture.controller.requestDelete("demo-rule-1").value().orElseThrow();

        ServiceResult<RuleLifecycleModel> result =
                fixture.controller.confirmDelete(confirmation, true);

        assertFalse(result.isSuccess());
        assertFalse(fixture.hasRule("demo-rule-1"));
        assertTrue(fixture.writer.isReadOnlyFault());
        assertTrue(fixture.writer.readOnlyFaultMessage().contains("撤销失败"));
        assertFalse(fixture.controller.model().blockedError().isEmpty());
        assertTrue(fixture.target("demo-target-a").targetAuthorized());
    }

    @Test
    public void externalTargetAuthorizationChangeBlocksMutationWithoutOverwritingIt() {
        Fixture fixture = fixture();
        fixture.controller.refresh();
        fixture.setTargetAuthorization("demo-target-a", false);

        ServiceResult<RuleLifecycleModel> result = fixture.controller.pause("demo-rule-1");

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("目标授权"));
        assertEquals("active", fixture.rule("demo-rule-1").status());
        assertFalse(fixture.target("demo-target-a").targetAuthorized());
        assertTrue(fixture.writer.lastKnownGood() == null);
    }

    @Test
    public void deleteFailsClosedWhenTargetChangesButAuthorizationBooleanStaysSame() {
        Fixture fixture = fixture();
        DeleteConfirmation confirmation =
                fixture.controller.requestDelete("demo-rule-1").value().orElseThrow();
        fixture.changeRuleTarget("demo-rule-1", "demo-target-g");
        int writesBefore = fixture.gateway.writeCount();

        ServiceResult<RuleLifecycleModel> result =
                fixture.controller.confirmDelete(confirmation, true);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("目标"));
        assertTrue(fixture.hasRule("demo-rule-1"));
        assertEquals("demo-target-g", fixture.rule("demo-rule-1").targetDeviceId());
        assertEquals(writesBefore, fixture.gateway.writeCount());
        assertEquals(0, fixture.permissions.revokeCount());
        assertTrue(fixture.target("demo-target-a").targetAuthorized());
        assertTrue(fixture.target("demo-target-g").targetAuthorized());
    }

    @Test
    public void undoFailsClosedWhenTargetChangesButAuthorizationBooleanStaysSame() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.changeRuleTarget("demo-rule-1", "demo-target-g");
        int writesBefore = fixture.gateway.writeCount();

        ServiceResult<RuleLifecycleModel> result = fixture.controller.undo(token);

        assertFalse(result.isSuccess());
        assertTrue(result.userMessage().contains("目标"));
        assertEquals("paused", fixture.rule("demo-rule-1").status());
        assertEquals("demo-target-g", fixture.rule("demo-rule-1").targetDeviceId());
        assertEquals(writesBefore, fixture.gateway.writeCount());
        assertTrue(fixture.target("demo-target-a").targetAuthorized());
        assertTrue(fixture.target("demo-target-g").targetAuthorized());
    }

    @Test
    public void replaceSnapshotPreservesPersistentFaultAcrossScenarioSwitch() {
        Fixture fixture = fixture();
        fixture.gateway.enterReadOnlyFault("演示持久只读故障。");
        RuleStoreSnapshot current = fixture.gateway.snapshot();
        NotificationFlowSnapshot flow = current.flowSnapshot();
        NotificationFlowSnapshot switched =
                new NotificationFlowSnapshot(
                        ConnectionState.TARGET_PROCESS_UNREACHABLE,
                        flow.stateVersion() + 1L,
                        false,
                        flow.targets(),
                        flow.rules(),
                        NotificationAccessState.REVOKED,
                        true);

        fixture.gateway.replaceSnapshot(
                new RuleStoreSnapshot(
                        switched,
                        current.sourceDevice(),
                        current.capabilities(),
                        current.unknownFieldsPresent(),
                        current.unexpressibleDslPresent()));

        assertEquals(
                "演示持久只读故障。",
                fixture.gateway.snapshot().readOnlyFaultMessage());
        assertTrue(fixture.gateway.snapshot().flowSnapshot().staleCache());
        assertEquals(
                NotificationAccessState.REVOKED,
                fixture.gateway.snapshot().flowSnapshot().notificationAccess());
    }

    @Test
    public void recreatedGatewayInheritsPersistentFaultAndRejectsWrites() {
        Fixture fixture = fixture();
        fixture.gateway.enterReadOnlyFault("演示持久只读故障。");
        FakeRuleStoreGateway recreated = new FakeRuleStoreGateway(fixture.gateway.store());
        RuleStoreSnapshot current = recreated.snapshot();
        recreated.replaceSnapshot(
                new RuleStoreSnapshot(
                        current.flowSnapshot(),
                        current.sourceDevice(),
                        current.capabilities(),
                        current.unknownFieldsPresent(),
                        current.unexpressibleDslPresent()));
        SafeNotificationRuleWriter recreatedWriter =
                new SafeNotificationRuleWriter(recreated, new FakeLastKnownGoodStore());
        RuleLifecycleController recreatedController =
                new RuleLifecycleController(
                        recreated,
                        recreatedWriter,
                        new FakeTargetPermissionGateway(recreated),
                        () -> 1_000L,
                        UNDO_WINDOW_MILLIS);

        ServiceResult<RuleLifecycleModel> result =
                recreatedController.pause("demo-rule-1");

        assertFalse(result.isSuccess());
        assertEquals(
                "演示持久只读故障。",
                recreated.snapshot().readOnlyFaultMessage());
        assertEquals(0, recreated.writeCount());
        assertEquals("active", rule(recreated, "demo-rule-1").status());
    }

    @Test
    public void successfulRecheckClearsTransientConflictAndReenablesActions() {
        Fixture fixture = fixture();
        fixture.controller.refresh();
        fixture.setTargetAuthorization("demo-target-a", false);
        ServiceResult<RuleLifecycleModel> failed = fixture.controller.pause("demo-rule-1");
        assertFalse(failed.isSuccess());
        assertFalse(fixture.controller.model().blockedError().isEmpty());
        assertFalse(fixture.controller.model().rule("demo-rule-1").orElseThrow().canPause());
        ServiceResult<RuleLifecycleModel> stillBlocked = fixture.controller.refresh();
        assertTrue(stillBlocked.isSuccess());
        assertFalse(stillBlocked.value().orElseThrow().blockedError().isEmpty());

        fixture.setTargetAuthorization("demo-target-a", true);
        ServiceResult<RuleLifecycleModel> recovered = fixture.controller.refresh();

        assertTrue(recovered.isSuccess());
        assertTrue(recovered.value().orElseThrow().blockedError().isEmpty());
        assertTrue(recovered.value().orElseThrow().rule("demo-rule-1").orElseThrow().canPause());
        assertTrue(fixture.controller.pause("demo-rule-1").isSuccess());
    }

    @Test
    public void missingOrRevokedNotificationAccessBlocksAffectedRulesAndExposesSettingsIntent() {
        for (NotificationAccessState access :
                new NotificationAccessState[] {
                    NotificationAccessState.MISSING, NotificationAccessState.REVOKED
                }) {
            Fixture fixture = fixture();
            fixture.setNotificationAccess(access);

            RuleLifecycleModel model = fixture.controller.refresh().value().orElseThrow();

            assertTrue(model.rule("demo-rule-1").orElseThrow().blocked());
            assertTrue(model.rule("demo-rule-1").orElseThrow().blockedMessage().contains("系统通知使用权"));
            assertEquals("受阻", model.rule("demo-rule-1").orElseThrow().displayStatus());
            assertFalse(model.rule("demo-rule-1").orElseThrow().canPause());
            assertFalse(model.rule("demo-rule-2").orElseThrow().blocked());
            NotificationAccessSettingsIntentSpec settings =
                    model.notificationAccessSettingsIntent().orElseThrow();
            assertEquals(
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                    settings.action());
        }
    }

    @Test(timeout = 5_000L)
    public void controllerSerializesRefreshAndStateChangesAroundPermissionRevocation()
            throws Exception {
        NotificationFlowSnapshot snapshot = augmentedDemoSnapshot();
        SourceDevice source = new SourceDevice("demo-source-phone", "Demo-Phone");
        FakeRuleStoreGateway gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                snapshot,
                                source,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        FakeTargetPermissionGateway delegate = new FakeTargetPermissionGateway(gateway);
        CountDownLatch revokeEntered = new CountDownLatch(1);
        CountDownLatch releaseRevoke = new CountDownLatch(1);
        TargetPermissionGateway blockingPermissions =
                targetId -> {
                    revokeEntered.countDown();
                    await(releaseRevoke);
                    return delegate.revoke(targetId);
                };
        RuleLifecycleController controller =
                new RuleLifecycleController(
                        gateway,
                        writer,
                        blockingPermissions,
                        new AtomicLong(1_000L)::get,
                        UNDO_WINDOW_MILLIS);
        controller.refresh();
        assertTrue(controller.resume("demo-rule-2").isSuccess());
        DeleteConfirmation confirmation =
                controller.requestDelete("demo-rule-1").value().orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ServiceResult<RuleLifecycleModel>> deletion =
                    executor.submit(() -> controller.confirmDelete(confirmation, true));
            assertTrue(revokeEntered.await(2L, TimeUnit.SECONDS));

            CountDownLatch pauseStarted = new CountDownLatch(1);
            CountDownLatch pauseFinished = new CountDownLatch(1);
            Future<ServiceResult<RuleLifecycleModel>> pause =
                    executor.submit(
                            () -> {
                                pauseStarted.countDown();
                                try {
                                    return controller.pause("demo-rule-2");
                                } finally {
                                    pauseFinished.countDown();
                                }
                            });
            assertTrue(pauseStarted.await(2L, TimeUnit.SECONDS));
            assertFalse(pauseFinished.await(300L, TimeUnit.MILLISECONDS));

            releaseRevoke.countDown();
            assertTrue(deletion.get(2L, TimeUnit.SECONDS).isSuccess());
            assertTrue(pause.get(2L, TimeUnit.SECONDS).isSuccess());
            assertEquals("paused", rule(gateway, "demo-rule-2").status());
            assertTrue(controller.model().undoToken().isPresent());
        } finally {
            releaseRevoke.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void sharedStoreRefreshUpdatesPageModelAndKeepsUndoToken() {
        Fixture fixture = fixture();

        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        RuleLifecycleModel refreshed = fixture.controller.refresh().value().orElseThrow();
        PanelDisplayModel page =
                PanelStateMapper.map(fixture.gateway.snapshot().flowSnapshot());

        PanelDisplayModel.RuleItem pageRule = null;
        for (PanelDisplayModel.RuleItem candidate : page.rules()) {
            if (candidate.ruleId().equals("demo-rule-1")) {
                pageRule = candidate;
                break;
            }
        }
        assertNotNull(pageRule);
        assertEquals("paused", pageRule.storedStatus());
        assertEquals("已暂停", pageRule.statusLabel());
        assertEquals(
                token.sequence(),
                refreshed.undoToken().orElseThrow().sequence());
        assertTrue(refreshed.canUndo());
    }

    @Test
    public void unwritableSnapshotsDisableEveryLifecycleActionButKeepUndoToken() {
        Fixture stale = fixture();
        preparePausedAndActiveRules(stale);
        stale.setStaleCache(true);
        assertLifecycleActionsDisabled(stale.controller.refresh().value().orElseThrow());

        Fixture unreachable = fixture();
        preparePausedAndActiveRules(unreachable);
        unreachable.setConnectionState(ConnectionState.TARGET_PROCESS_UNREACHABLE);
        assertLifecycleActionsDisabled(unreachable.controller.refresh().value().orElseThrow());

        Fixture connectionCapability = fixture();
        preparePausedAndActiveRules(connectionCapability);
        connectionCapability.setConnectionState(ConnectionState.CAPABILITY_INSUFFICIENT);
        assertLifecycleActionsDisabled(
                connectionCapability.controller.refresh().value().orElseThrow());

        Fixture evidenceCapability = fixture();
        preparePausedAndActiveRules(evidenceCapability);
        evidenceCapability.setCapabilities(
                new CapabilityEvidence(
                        "demo-version", Collections.singleton("demo-version"), Collections.emptyMap()));
        assertLifecycleActionsDisabled(
                evidenceCapability.controller.refresh().value().orElseThrow());

        Fixture compatibility = fixture();
        preparePausedAndActiveRules(compatibility);
        compatibility.setConnectionState(ConnectionState.COMPATIBILITY_UNVERIFIED);
        RuleLifecycleModel compatible =
                compatibility.controller.refresh().value().orElseThrow();
        assertTrue(compatible.blockedError().isEmpty());
        assertTrue(compatible.rule("demo-rule-1").orElseThrow().canResume());
        assertTrue(compatible.rule("demo-rule-2").orElseThrow().canPause());
        assertTrue(compatible.canUndo());
    }

    @Test
    public void notificationAccessLossDisablesWritesAndKeepsUndoToken() {
        Fixture fixture = fixture();
        preparePausedAndActiveRules(fixture);
        fixture.setNotificationAccess(NotificationAccessState.MISSING);

        RuleLifecycleModel model = fixture.controller.refresh().value().orElseThrow();

        assertFalse(model.rule("demo-rule-1").orElseThrow().canResume());
        assertFalse(model.rule("demo-rule-2").orElseThrow().canPause());
        assertFalse(model.rule("demo-rule-1").orElseThrow().canDelete());
        assertFalse(model.canUndo());
        assertTrue(model.undoToken().isPresent());
    }

    @Test
    public void directLifecycleCallsFailBeforeStoreWriteWhenAuthorizationIsMissing() {
        Fixture unauthorizedTarget = fixture();
        unauthorizedTarget.controller.refresh();
        ServiceResult<RuleLifecycleModel> targetResult =
                unauthorizedTarget.controller.pause("demo-rule-3");
        assertFalse(targetResult.isSuccess());
        assertEquals(0, unauthorizedTarget.gateway.writeCount());

        Fixture missingAccess = fixture();
        missingAccess.setNotificationAccess(NotificationAccessState.MISSING);
        missingAccess.controller.refresh();
        ServiceResult<RuleLifecycleModel> accessResult =
                missingAccess.controller.pause("demo-rule-1");
        assertFalse(accessResult.isSuccess());
        assertEquals(0, missingAccess.gateway.writeCount());
    }

    @Test
    public void recheckDoesNotClearConflictWhenTargetDisappears() {
        Fixture fixture = fixture();
        fixture.controller.refresh();
        fixture.setTargetAuthorization("demo-target-a", false);
        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        fixture.removeTarget("demo-target-a");

        RuleLifecycleModel refreshed = fixture.controller.refresh().value().orElseThrow();

        assertFalse(refreshed.blockedError().isEmpty());
        assertFalse(refreshed.canUndo());
    }

    @Test
    public void recheckDoesNotClearConflictWhenRuleTargetStillMismatches() {
        Fixture fixture = fixture();
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.changeRuleTarget("demo-rule-1", "demo-target-g");
        assertFalse(fixture.controller.undo(token).isSuccess());

        RuleLifecycleModel refreshed = fixture.controller.refresh().value().orElseThrow();

        assertFalse(refreshed.blockedError().isEmpty());
        assertEquals("demo-target-g", fixture.rule("demo-rule-1").targetDeviceId());
    }

    @Test
    public void recheckWaitsForStaleSnapshotToRecoverBeforeClearingConflict() {
        Fixture fixture = fixture();
        fixture.controller.refresh();
        fixture.setStaleCache(true);
        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        assertFalse(fixture.controller.refresh().value().orElseThrow().blockedError().isEmpty());

        fixture.setStaleCache(false);
        RuleLifecycleModel recovered = fixture.controller.refresh().value().orElseThrow();

        assertTrue(recovered.blockedError().isEmpty());
        assertTrue(recovered.rule("demo-rule-1").orElseThrow().canPause());
    }

    @Test
    public void recheckWaitsForCapabilityRecoveryBeforeClearingConflict() {
        Fixture fixture = fixture();
        fixture.controller.refresh();
        fixture.setCapabilities(
                new CapabilityEvidence(
                        "demo-version", Collections.singleton("demo-version"), Collections.emptyMap()));
        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        assertFalse(fixture.controller.refresh().value().orElseThrow().blockedError().isEmpty());

        fixture.setCapabilities(CapabilityEvidence.demoComplete());
        RuleLifecycleModel recovered = fixture.controller.refresh().value().orElseThrow();

        assertTrue(recovered.blockedError().isEmpty());
        assertTrue(recovered.rule("demo-rule-1").orElseThrow().canPause());
    }

    private static Fixture fixture() {
        NotificationFlowSnapshot snapshot = augmentedDemoSnapshot();
        SourceDevice source = new SourceDevice("demo-source-phone", "Demo-Phone");
        FakeRuleStoreGateway gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                snapshot,
                                source,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        FakeTargetPermissionGateway permissions = new FakeTargetPermissionGateway(gateway);
        AtomicLong clock = new AtomicLong(1_000L);
        RuleLifecycleController controller =
                new RuleLifecycleController(
                        gateway, writer, permissions, clock::get, UNDO_WINDOW_MILLIS);
        return new Fixture(snapshot, gateway, writer, permissions, clock, controller);
    }

    private static NotificationFlowSnapshot augmentedDemoSnapshot() {
        NotificationFlowSnapshot base =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        List<TargetDevice> targets = new ArrayList<>(base.targets());
        targets.add(
                new TargetDevice(
                        "demo-target-f",
                        "Demo-Pad-B",
                        DeviceType.PAD,
                        true,
                        true,
                        false,
                        true));
        targets.add(
                new TargetDevice(
                        "demo-target-g",
                        "Demo-PC-B",
                        DeviceType.PC,
                        true,
                        true,
                        true,
                        true));
        return new NotificationFlowSnapshot(
                base.connectionState(),
                base.stateVersion(),
                base.compatibilityUnverified(),
                targets,
                base.rules(),
                base.notificationAccess(),
                base.staleCache());
    }

    private static NotificationRule rule(FakeRuleStoreGateway gateway, String ruleId) {
        for (NotificationRule rule : gateway.currentRules()) {
            if (rule.ruleId().equals(ruleId)) {
                return rule;
            }
        }
        throw new AssertionError("Missing rule " + ruleId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test gate.", exception);
        }
    }

    private static void preparePausedAndActiveRules(Fixture fixture) {
        assertTrue(fixture.controller.pause("demo-rule-1").isSuccess());
        assertTrue(fixture.controller.resume("demo-rule-2").isSuccess());
    }

    private static void assertLifecycleActionsDisabled(RuleLifecycleModel model) {
        assertFalse(model.blockedError().isEmpty());
        assertFalse(model.rule("demo-rule-1").orElseThrow().canResume());
        assertFalse(model.rule("demo-rule-2").orElseThrow().canPause());
        assertFalse(model.rule("demo-rule-1").orElseThrow().canDelete());
        assertFalse(model.canUndo());
        assertTrue(model.undoToken().isPresent());
    }

    private static void assertUndoTokenRetained(Fixture fixture, RuleUndoToken token) {
        assertEquals(
                token.sequence(),
                fixture.controller.model().undoToken().orElseThrow().sequence());
    }

    private static final class Fixture {
        private final NotificationFlowSnapshot snapshot;
        private final FakeRuleStoreGateway gateway;
        private final SafeNotificationRuleWriter writer;
        private final FakeTargetPermissionGateway permissions;
        private final AtomicLong clock;
        private final RuleLifecycleController controller;

        private Fixture(
                NotificationFlowSnapshot snapshot,
                FakeRuleStoreGateway gateway,
                SafeNotificationRuleWriter writer,
                FakeTargetPermissionGateway permissions,
                AtomicLong clock,
                RuleLifecycleController controller) {
            this.snapshot = snapshot;
            this.gateway = gateway;
            this.writer = writer;
            this.permissions = permissions;
            this.clock = clock;
            this.controller = controller;
        }

        private NotificationRule rule(String ruleId) {
            for (NotificationRule rule : gateway.currentRules()) {
                if (rule.ruleId().equals(ruleId)) {
                    return rule;
                }
            }
            return null;
        }

        private boolean hasRule(String ruleId) {
            return rule(ruleId) != null;
        }

        private TargetDevice target(String targetId) {
            for (TargetDevice target : gateway.snapshot().flowSnapshot().targets()) {
                if (target.deviceId().equals(targetId)) {
                    return target;
                }
            }
            throw new AssertionError("Missing target " + targetId);
        }

        private void setTargetAuthorization(String targetId, boolean authorized) {
            TargetDevice current = target(targetId);
            TargetDevice replacement =
                    new TargetDevice(
                            current.deviceId(),
                            current.displayName(),
                            current.type(),
                            current.online(),
                            current.reachable(),
                            authorized,
                            current.receiveCapability());
            gateway.replaceSnapshot(gateway.snapshot().withTarget(replacement));
        }

        private void setNotificationAccess(NotificationAccessState access) {
            java.util.Map<String, Boolean> authorizations = new java.util.LinkedHashMap<>();
            for (TargetDevice target : gateway.snapshot().flowSnapshot().targets()) {
                authorizations.put(target.deviceId(), target.targetAuthorized());
            }
            gateway.replaceSnapshot(
                    gateway.snapshot().withPermissions(authorizations, access));
        }

        private void changeRuleTarget(String ruleId, String targetDeviceId) {
            NotificationRule current = rule(ruleId);
            TargetDevice target = target(targetDeviceId);
            List<NotificationRule> rules = new ArrayList<>();
            for (NotificationRule candidate : gateway.currentRules()) {
                if (candidate.ruleId().equals(ruleId)) {
                    rules.add(
                            new NotificationRule(
                                    current.ruleId(),
                                    current.sourceDeviceId(),
                                    current.sourceDeviceName(),
                                    target.deviceId(),
                                    target.displayName(),
                                    current.status(),
                                    current.summary(),
                                    current.dslJson(),
                                    current.createdAt()));
                } else {
                    rules.add(candidate);
                }
            }
            gateway.replaceSnapshot(gateway.snapshot().withRules(rules));
        }

        private void removeTarget(String targetDeviceId) {
            RuleStoreSnapshot current = gateway.snapshot();
            NotificationFlowSnapshot flow = current.flowSnapshot();
            List<TargetDevice> targets = new ArrayList<>();
            for (TargetDevice target : flow.targets()) {
                if (!target.deviceId().equals(targetDeviceId)) {
                    targets.add(target);
                }
            }
            replaceFlow(
                    new NotificationFlowSnapshot(
                            flow.connectionState(),
                            flow.stateVersion() + 1L,
                            flow.compatibilityUnverified(),
                            targets,
                            flow.rules(),
                            flow.notificationAccess(),
                            flow.staleCache()),
                    current.capabilities());
        }

        private void setStaleCache(boolean staleCache) {
            RuleStoreSnapshot current = gateway.snapshot();
            NotificationFlowSnapshot flow = current.flowSnapshot();
            replaceFlow(
                    new NotificationFlowSnapshot(
                            flow.connectionState(),
                            flow.stateVersion() + 1L,
                            flow.compatibilityUnverified(),
                            flow.targets(),
                            flow.rules(),
                            flow.notificationAccess(),
                            staleCache),
                    current.capabilities());
        }

        private void setConnectionState(ConnectionState connectionState) {
            RuleStoreSnapshot current = gateway.snapshot();
            NotificationFlowSnapshot flow = current.flowSnapshot();
            replaceFlow(
                    new NotificationFlowSnapshot(
                            connectionState,
                            flow.stateVersion() + 1L,
                            connectionState == ConnectionState.COMPATIBILITY_UNVERIFIED,
                            flow.targets(),
                            flow.rules(),
                            flow.notificationAccess(),
                            flow.staleCache()),
                    current.capabilities());
        }

        private void setCapabilities(CapabilityEvidence capabilities) {
            RuleStoreSnapshot current = gateway.snapshot();
            replaceFlow(current.flowSnapshot(), capabilities);
        }

        private void replaceFlow(
                NotificationFlowSnapshot flow, CapabilityEvidence capabilities) {
            RuleStoreSnapshot current = gateway.snapshot();
            gateway.replaceSnapshot(
                    new RuleStoreSnapshot(
                            flow,
                            current.sourceDevice(),
                            capabilities,
                            current.unknownFieldsPresent(),
                            current.unexpressibleDslPresent()));
        }
    }
}
