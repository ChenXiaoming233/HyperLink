package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.fake.FakeTargetPermissionGateway;
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
            assertFalse(model.rule("demo-rule-2").orElseThrow().blocked());
            NotificationAccessSettingsIntentSpec settings =
                    model.notificationAccessSettingsIntent().orElseThrow();
            assertEquals(
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                    settings.action());
        }
    }

    private static Fixture fixture() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
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
    }
}
