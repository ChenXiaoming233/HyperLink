package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleRecoveryModel;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteRecoveryController;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.fake.FakeTargetPermissionGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class RuleLifecycleRecoveryTest {
    @Test
    public void pauseFirstFailureThenControlledRetryCreatesUndoToken() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();

        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.controller.recoveryModel().state());
        assertFalse(fixture.controller.model().rule("demo-rule-1").orElseThrow().canPause());

        ServiceResult<RuleLifecycleModel> retry =
                fixture.controller.retryMutationRecovery();

        assertTrue(retry.userMessage(), retry.isSuccess());
        assertEquals("paused", fixture.rule("demo-rule-1").status());
        assertTrue(retry.value().orElseThrow().canUndo());
        assertEquals(0, fixture.permissions.revokeCount());
    }

    @Test
    public void resumeSecondFailureAutomaticallyRollsBackWithoutNewUndo() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptEveryWrite();

        assertFalse(fixture.controller.resume("demo-rule-2").isSuccess());
        assertFalse(fixture.controller.retryMutationRecovery().isSuccess());

        assertEquals(
                RuleRecoveryModel.State.ROLLED_BACK,
                fixture.controller.recoveryModel().state());
        assertEquals("paused", fixture.rule("demo-rule-2").status());
        assertFalse(fixture.controller.model().canUndo());
    }

    @Test
    public void deleteRetrySuccessContinuesTargetAuthorizationRevocation() {
        Fixture fixture = fixture("demo-version-tested");
        DeleteConfirmation confirmation =
                fixture.controller.requestDelete("demo-rule-1").value().orElseThrow();
        fixture.gateway.corruptNextWrite();

        assertFalse(fixture.controller.confirmDelete(confirmation, true).isSuccess());
        assertEquals(0, fixture.permissions.revokeCount());
        assertTrue(fixture.controller.retryMutationRecovery().isSuccess());

        assertFalse(fixture.hasRule("demo-rule-1"));
        assertEquals(1, fixture.permissions.revokeCount());
        assertFalse(fixture.target("demo-target-a").targetAuthorized());
    }

    @Test
    public void deleteRetryFailureRollsBackAndNeverRevokesAuthorization() {
        Fixture fixture = fixture("demo-version-tested");
        DeleteConfirmation confirmation =
                fixture.controller.requestDelete("demo-rule-1").value().orElseThrow();
        fixture.gateway.corruptEveryWrite();

        assertFalse(fixture.controller.confirmDelete(confirmation, true).isSuccess());
        assertFalse(fixture.controller.retryMutationRecovery().isSuccess());

        assertEquals(
                RuleRecoveryModel.State.ROLLED_BACK,
                fixture.controller.recoveryModel().state());
        assertTrue(fixture.hasRule("demo-rule-1"));
        assertEquals(0, fixture.permissions.revokeCount());
        assertTrue(fixture.target("demo-target-a").targetAuthorized());
    }

    @Test
    public void pendingRecoveryRejectsPauseResumeDeleteUndoAndSaveWithoutWriting() {
        Fixture fixture = fixture("demo-version-tested");
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        DeleteConfirmation confirmation =
                fixture.controller.requestDelete("demo-rule-2").value().orElseThrow();
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.resume("demo-rule-2").isSuccess());
        int writesBefore = fixture.gateway.writeCount();

        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        assertFalse(fixture.controller.resume("demo-rule-2").isSuccess());
        assertFalse(fixture.controller.confirmDelete(confirmation, true).isSuccess());
        assertFalse(fixture.controller.undo(token).isSuccess());
        assertEquals(writesBefore, fixture.gateway.writeCount());
    }

    @Test
    public void retriedLifecycleMutationUsesCurrentCompatibilityNotes() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        RuleStoreSnapshot current = fixture.gateway.snapshot();
        fixture.gateway.replaceSnapshot(
                current.withCapabilities(
                        CapabilityEvidence.demoComplete(
                                "demo-version-unknown",
                                Set.of("demo-version-tested"))));

        RuleLifecycleModel retried =
                fixture.controller.retryMutationRecovery().value().orElseThrow();

        assertTrue(
                retried.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    @Test
    public void lifecycleMutationResultNotesAreKeptForUnknownVersion() {
        Fixture fixture = fixture("demo-version-unknown");

        RuleLifecycleModel paused =
                fixture.controller.pause("demo-rule-1").value().orElseThrow();

        assertTrue(
                paused.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    @Test
    public void undoFirstFailureThenRetryKeepsTokenUntilSuccess() {
        Fixture fixture = fixture("demo-version-tested");
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.gateway.corruptNextWrite();

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertEquals(2, fixture.lkgStore.saveCount());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.controller.recoveryModel().state());

        assertTrue(fixture.controller.retryMutationRecovery().isSuccess());
        assertEquals("active", fixture.rule("demo-rule-1").status());
        assertFalse(fixture.controller.model().canUndo());
        assertEquals(2, fixture.lkgStore.saveCount());
    }

    @Test
    public void undoSecondFailureAutomaticallyRollsBackAndRetainsToken() {
        Fixture fixture = fixture("demo-version-tested");
        RuleUndoToken token =
                fixture.controller
                        .pause("demo-rule-1")
                        .value()
                        .orElseThrow()
                        .undoToken()
                        .orElseThrow();
        fixture.gateway.corruptEveryWrite();

        assertFalse(fixture.controller.undo(token).isSuccess());
        assertFalse(fixture.controller.retryMutationRecovery().isSuccess());

        assertEquals(
                RuleRecoveryModel.State.ROLLED_BACK,
                fixture.controller.recoveryModel().state());
        assertEquals("paused", fixture.rule("demo-rule-1").status());
        assertEquals(
                token.sequence(),
                fixture.controller.model().undoToken().orElseThrow().sequence());
        assertEquals(2, fixture.lkgStore.saveCount());
    }

    @Test
    public void pauseResumeDeleteSaveLkgOnceAcrossControlledRetry() {
        assertMutationRetrySavesLkgOnce(Mutation.PAUSE);
        assertMutationRetrySavesLkgOnce(Mutation.RESUME);
        assertMutationRetrySavesLkgOnce(Mutation.DELETE);
    }

    @Test
    public void ordinaryWritePendingRejectsLifecycleUntilWriteRecoveryCompletes() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(
                fixture.recovery.saveStructuredRule(
                                fixture.selection, fixture.draft)
                        .isSuccess());
        int writesBeforeLifecycleAttempt = fixture.gateway.writeCount();

        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());

        assertFalse(fixture.controller.hasPendingRecovery());
        assertTrue(fixture.recovery.hasPendingWriteRecovery());
        RuleLifecycleModel blocked =
                fixture.controller.refresh().value().orElseThrow();
        assertTrue(blocked.blockedError().contains("普通规则写入"));
        assertFalse(blocked.rule("demo-rule-1").orElseThrow().canPause());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.recovery.model().state());
        assertEquals(writesBeforeLifecycleAttempt, fixture.gateway.writeCount());
        assertTrue(fixture.recovery.retry().isSuccess());
        assertTrue(fixture.controller.pause("demo-rule-1").isSuccess());
    }

    @Test
    public void lifecyclePendingRejectsOrdinaryWriteRecovery() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        int writesBeforeSave = fixture.gateway.writeCount();

        assertFalse(
                fixture.recovery.saveStructuredRule(
                                fixture.selection, fixture.draft)
                        .isSuccess());

        assertTrue(fixture.controller.hasPendingRecovery());
        assertEquals(writesBeforeSave, fixture.gateway.writeCount());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.recovery.model().state());
    }

    @Test
    public void lifecycleDebugModeTurnsOffAfterSuccessRollbackPageExitAndRestart() {
        Fixture success = fixture("demo-version-tested");
        success.gateway.corruptNextWrite();
        assertFalse(success.controller.pause("demo-rule-1").isSuccess());
        success.recovery.setDebugMode(true);
        assertTrue(success.controller.retryMutationRecovery().isSuccess());
        assertFalse(success.recovery.model().debugModeEnabled());

        Fixture rollback = fixture("demo-version-tested");
        rollback.gateway.corruptEveryWrite();
        assertFalse(rollback.controller.resume("demo-rule-2").isSuccess());
        rollback.recovery.setDebugMode(true);
        assertFalse(rollback.controller.retryMutationRecovery().isSuccess());
        assertFalse(rollback.recovery.model().debugModeEnabled());

        Fixture pageExit = fixture("demo-version-tested");
        RuleUndoToken token =
                pageExit.controller.pause("demo-rule-1").value().orElseThrow()
                        .undoToken().orElseThrow();
        pageExit.recovery.setDebugMode(true);
        pageExit.recovery.onPageExit();
        pageExit.recovery.onPageExit();
        assertFalse(pageExit.recovery.model().debugModeEnabled());
        assertEquals(
                token.sequence(),
                pageExit.controller.model().undoToken().orElseThrow().sequence());
        pageExit.recovery.setDebugMode(true);
        pageExit.recovery.onProcessRestart();
        assertFalse(pageExit.recovery.model().debugModeEnabled());
    }

    private static Fixture fixture(String version) {
        NotificationFlowSnapshot snapshot = augmentedDemoSnapshot();
        SourceDevice source = new SourceDevice("demo-source-phone", "Demo-Phone");
        FakeRuleStoreGateway gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                snapshot,
                                source,
                                CapabilityEvidence.demoComplete(
                                        version, Set.of("demo-version-tested")),
                                false,
                                false));
        FakeLastKnownGoodStore lkgStore = new FakeLastKnownGoodStore();
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, lkgStore);
        RuleWriteRecoveryController recovery =
                new RuleWriteRecoveryController(writer);
        FakeTargetPermissionGateway permissions = new FakeTargetPermissionGateway(gateway);
        AtomicLong clock = new AtomicLong(1_000L);
        RuleLifecycleController controller =
                new RuleLifecycleController(
                        gateway,
                        recovery,
                        permissions,
                        clock::get,
                        30_000L);
        ConfirmedTargetSelection selection =
                new TargetSelectionFlow(snapshot)
                        .select(source, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 400L);
        return new Fixture(
                gateway, lkgStore, permissions, recovery, controller, selection, draft);
    }

    private static void assertMutationRetrySavesLkgOnce(Mutation mutation) {
        Fixture fixture = fixture("demo-version-tested");
        DeleteConfirmation confirmation = null;
        if (mutation == Mutation.DELETE) {
            confirmation =
                    fixture.controller.requestDelete("demo-rule-1").value().orElseThrow();
        }
        fixture.gateway.corruptNextWrite();

        if (mutation == Mutation.PAUSE) {
            assertFalse(fixture.controller.pause("demo-rule-1").isSuccess());
        } else if (mutation == Mutation.RESUME) {
            assertFalse(fixture.controller.resume("demo-rule-2").isSuccess());
        } else {
            assertFalse(
                    fixture.controller.confirmDelete(confirmation, true).isSuccess());
        }
        assertEquals(1, fixture.lkgStore.saveCount());
        assertTrue(fixture.controller.retryMutationRecovery().isSuccess());
        assertEquals(1, fixture.lkgStore.saveCount());
    }

    private static NotificationFlowSnapshot augmentedDemoSnapshot() {
        NotificationFlowSnapshot base =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        List<TargetDevice> targets = new ArrayList<>(base.targets());
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

    private static final class Fixture {
        private final FakeRuleStoreGateway gateway;
        private final FakeLastKnownGoodStore lkgStore;
        private final FakeTargetPermissionGateway permissions;
        private final RuleWriteRecoveryController recovery;
        private final RuleLifecycleController controller;
        private final ConfirmedTargetSelection selection;
        private final StructuredRuleDraft draft;

        private Fixture(
                FakeRuleStoreGateway gateway,
                FakeLastKnownGoodStore lkgStore,
                FakeTargetPermissionGateway permissions,
                RuleWriteRecoveryController recovery,
                RuleLifecycleController controller,
                ConfirmedTargetSelection selection,
                StructuredRuleDraft draft) {
            this.gateway = gateway;
            this.lkgStore = lkgStore;
            this.permissions = permissions;
            this.recovery = recovery;
            this.controller = controller;
            this.selection = selection;
            this.draft = draft;
        }

        private NotificationRule rule(String ruleId) {
            for (NotificationRule rule : gateway.currentRules()) {
                if (rule.ruleId().equals(ruleId)) {
                    return rule;
                }
            }
            throw new AssertionError("Missing rule " + ruleId);
        }

        private boolean hasRule(String ruleId) {
            for (NotificationRule rule : gateway.currentRules()) {
                if (rule.ruleId().equals(ruleId)) {
                    return true;
                }
            }
            return false;
        }

        private TargetDevice target(String targetId) {
            for (TargetDevice target : gateway.snapshot().flowSnapshot().targets()) {
                if (target.deviceId().equals(targetId)) {
                    return target;
                }
            }
            throw new AssertionError("Missing target " + targetId);
        }
    }

    private enum Mutation {
        PAUSE,
        RESUME,
        DELETE
    }
}
