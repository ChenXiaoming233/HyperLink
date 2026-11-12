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
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleRecoveryModel;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteRecoveryController;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
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
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
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
        return new Fixture(gateway, permissions, controller);
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
        private final FakeTargetPermissionGateway permissions;
        private final RuleLifecycleController controller;

        private Fixture(
                FakeRuleStoreGateway gateway,
                FakeTargetPermissionGateway permissions,
                RuleLifecycleController controller) {
            this.gateway = gateway;
            this.permissions = permissions;
            this.controller = controller;
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
}
