package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class RuleWriteRecoveryMutationTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void pauseFirstFailureThenControlledRetrySucceeds() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();

        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-1",
                                "demo-target-a",
                                "active",
                                "paused",
                                true)
                        .isSuccess());
        assertEquals(RuleRecoveryModel.State.AWAITING_DECISION, fixture.controller.model().state());

        ServiceResult<RuleMutationResult> retried = fixture.controller.retryMutation();

        assertTrue(retried.isSuccess());
        assertEquals("paused", rule(fixture.gateway, "demo-rule-1").status());
        assertEquals(RuleRecoveryModel.State.SUCCESS, fixture.controller.model().state());
    }

    @Test
    public void resumeSecondFailureAutomaticallyRollsBack() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        fixture.gateway.corruptEveryWrite();

        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-2",
                                "demo-target-b",
                                "paused",
                                "active",
                                true)
                        .isSuccess());
        assertFalse(fixture.controller.retryMutation().isSuccess());

        assertEquals(RuleRecoveryModel.State.ROLLED_BACK, fixture.controller.model().state());
        assertSameRules(original, fixture.gateway.currentRules());
        assertEquals("paused", rule(fixture.gateway, "demo-rule-2").status());
    }

    @Test
    public void deleteFirstFailureThenControlledRetrySucceeds() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();

        assertFalse(
                fixture.controller
                        .deleteRule("demo-rule-1", "demo-target-a", true)
                        .isSuccess());
        assertTrue(fixture.controller.retryMutation().isSuccess());

        assertEquals(2, fixture.gateway.currentRules().size());
        assertFalse(hasRule(fixture.gateway, "demo-rule-1"));
    }

    @Test
    public void deleteSecondFailureAutomaticallyRollsBackAndKeepsRule() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        fixture.gateway.corruptEveryWrite();

        assertFalse(
                fixture.controller
                        .deleteRule("demo-rule-1", "demo-target-a", true)
                        .isSuccess());
        assertFalse(fixture.controller.retryMutation().isSuccess());

        assertEquals(RuleRecoveryModel.State.ROLLED_BACK, fixture.controller.model().state());
        assertSameRules(original, fixture.gateway.currentRules());
        assertTrue(hasRule(fixture.gateway, "demo-rule-1"));
    }

    @Test
    public void sameVersionExternalRuleChangeIsUnsafeAndRetryNeverOverwritesIt() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> externalRules =
                externalValidRules(fixture.gateway.currentRules());
        fixture.gateway.replaceRulesAfterNextWrite(externalRules);

        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-1",
                                "demo-target-a",
                                "active",
                                "paused",
                                true)
                        .isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.CURRENT_STATE_UNSAFE, model.state());
        assertFalse(model.canRetry());
        assertFalse(model.canRollback());
        int writesBeforeRetry = fixture.gateway.writeCount();

        assertFalse(fixture.controller.retryMutation().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
        assertSameRules(externalRules, fixture.gateway.currentRules());
    }

    @Test
    public void pendingMutationRecoveryRejectsEveryNewWrite() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-1",
                                "demo-target-a",
                                "active",
                                "paused",
                                true)
                        .isSuccess());
        int writesBefore = fixture.gateway.writeCount();

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());
        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-2",
                                "demo-target-b",
                                "paused",
                                "active",
                                true)
                        .isSuccess());
        assertFalse(
                fixture.controller
                        .deleteRule("demo-rule-1", "demo-target-a", true)
                        .isSuccess());
        assertEquals(writesBefore, fixture.gateway.writeCount());
    }

    @Test
    public void retryMutationRecomputesCompatibilityFromCurrentSnapshot() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-1",
                                "demo-target-a",
                                "active",
                                "paused",
                                true)
                        .isSuccess());
        setCapabilityVersion(fixture.gateway, "demo-version-unknown");

        ServiceResult<RuleMutationResult> retry = fixture.controller.retryMutation();
        assertTrue(retry.userMessage(), retry.isSuccess());
        RuleMutationResult retried = retry.value().orElseThrow();

        assertTrue(retried.compatibilityUnverified());
        assertTrue(
                retried.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    @Test
    public void mutationNotesContainCompatibilityWarningForUnknownVersion() {
        Fixture fixture = fixture("demo-version-unknown");

        RuleMutationResult paused =
                fixture.controller
                        .changeRuleStatus(
                                "demo-rule-1",
                                "demo-target-a",
                                "active",
                                "paused",
                                true)
                        .value()
                        .orElseThrow();

        assertTrue(paused.compatibilityUnverified());
        assertTrue(
                paused.notes().stream()
                        .anyMatch(note -> note.contains("兼容性未经完整验收")));
    }

    private static Fixture fixture(String version) {
        NotificationFlowSnapshot flow =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        RuleStoreSnapshot snapshot =
                new RuleStoreSnapshot(
                        flow,
                        SOURCE,
                        CapabilityEvidence.demoComplete(version, Set.of("demo-version-tested")),
                        false,
                        false);
        FakeRuleStoreGateway gateway = new FakeRuleStoreGateway(snapshot);
        FakeLastKnownGoodStore lkgStore = new FakeLastKnownGoodStore();
        RuleWriteRecoveryController controller =
                new RuleWriteRecoveryController(gateway, lkgStore);
        ConfirmedTargetSelection selection =
                new TargetSelectionFlow(flow)
                        .select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 200L);
        return new Fixture(gateway, controller, selection, draft);
    }

    private static void setCapabilityVersion(
            FakeRuleStoreGateway gateway, String version) {
        RuleStoreSnapshot current = gateway.snapshot();
        gateway.replaceSnapshot(
                current.withCapabilities(
                        CapabilityEvidence.demoComplete(
                                version, Set.of("demo-version-tested"))));
    }

    private static List<NotificationRule> externalValidRules(
            List<NotificationRule> rules) {
        List<NotificationRule> changed = new ArrayList<>(rules);
        NotificationRule first = changed.get(0);
        changed.set(
                0,
                new NotificationRule(
                        first.ruleId(),
                        first.sourceDeviceId(),
                        first.sourceDeviceName(),
                        first.targetDeviceId(),
                        first.targetDeviceName(),
                        first.status(),
                        first.summary() + " 外部更新。",
                        first.dslJson(),
                        first.createdAt()));
        return changed;
    }

    private static NotificationRule rule(FakeRuleStoreGateway gateway, String ruleId) {
        for (NotificationRule rule : gateway.currentRules()) {
            if (rule.ruleId().equals(ruleId)) {
                return rule;
            }
        }
        throw new AssertionError("Missing rule " + ruleId);
    }

    private static boolean hasRule(FakeRuleStoreGateway gateway, String ruleId) {
        for (NotificationRule rule : gateway.currentRules()) {
            if (rule.ruleId().equals(ruleId)) {
                return true;
            }
        }
        return false;
    }

    private static void assertSameRules(
            List<NotificationRule> expected, List<NotificationRule> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            NotificationRule left = expected.get(index);
            NotificationRule right = actual.get(index);
            assertEquals(left.ruleId(), right.ruleId());
            assertEquals(left.targetDeviceId(), right.targetDeviceId());
            assertEquals(left.status(), right.status());
            assertEquals(left.summary(), right.summary());
            assertEquals(left.dslJson(), right.dslJson());
            assertEquals(left.createdAt(), right.createdAt());
        }
    }

    private static final class Fixture {
        private final FakeRuleStoreGateway gateway;
        private final RuleWriteRecoveryController controller;
        private final ConfirmedTargetSelection selection;
        private final StructuredRuleDraft draft;

        private Fixture(
                FakeRuleStoreGateway gateway,
                RuleWriteRecoveryController controller,
                ConfirmedTargetSelection selection,
                StructuredRuleDraft draft) {
            this.gateway = gateway;
            this.controller = controller;
            this.selection = selection;
            this.draft = draft;
        }
    }
}
