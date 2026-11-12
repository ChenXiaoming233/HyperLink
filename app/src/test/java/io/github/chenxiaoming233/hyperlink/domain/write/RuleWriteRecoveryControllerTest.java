package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class RuleWriteRecoveryControllerTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void firstValidationFailureOffersRecommendedRollbackAndControlledRetrySuccess() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();

        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());

        RuleRecoveryModel waiting = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.AWAITING_DECISION, waiting.state());
        assertTrue(waiting.canRollback());
        assertTrue(waiting.canRetry());
        assertTrue(waiting.message().contains("推荐立即回滚"));

        ServiceResult<RuleCreationResult> retried = fixture.controller.retry();

        assertTrue(retried.isSuccess());
        assertEquals(3, fixture.gateway.currentRules().size());
        assertEquals(RuleRecoveryModel.State.SUCCESS, fixture.controller.model().state());
        assertFalse(fixture.controller.model().debugModeEnabled());
    }

    @Test
    public void allNotificationsCreationUsesTheSameRecoveryFlow() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();

        assertFalse(
                fixture.controller
                        .createAllNotificationsRule(fixture.selection)
                        .isSuccess());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.controller.model().state());

        assertTrue(fixture.controller.retry().isSuccess());
        assertEquals(3, fixture.gateway.currentRules().size());
    }

    @Test
    public void secondFailureRollsBackAutomaticallyWithoutReplacingOriginalLkg() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        String originalLkg = fixture.gateway.serializedJson();
        fixture.gateway.corruptEveryWrite();

        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());
        assertSameRules(original, fixture.lkgStore.load().rules());
        assertEquals(originalLkg, fixture.lkgStore.load().serializedJson());

        ServiceResult<RuleCreationResult> retried = fixture.controller.retry();

        assertFalse(retried.isSuccess());
        assertEquals(RuleRecoveryModel.State.ROLLED_BACK, fixture.controller.model().state());
        assertSameRules(original, fixture.gateway.currentRules());
        assertEquals(originalLkg, fixture.lkgStore.load().serializedJson());
        assertEquals(3, fixture.gateway.writeCount());
    }

    @Test
    public void unsafeCurrentStateBlocksControlledRetryWithoutWriting() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());
        RuleStoreSnapshot unsafe =
                fixture.gateway.snapshot().withSafetyFlags(true, false);
        fixture.gateway.replaceSnapshot(unsafe);
        int writesBeforeRetry = fixture.gateway.writeCount();

        assertFalse(fixture.controller.model().canRetry());
        assertFalse(fixture.controller.model().canRollback());
        assertFalse(fixture.controller.model().canRedetect());
        assertFalse(fixture.controller.redetect().isSuccess());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                fixture.controller.model().state());
        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
        assertTrue(fixture.controller.model().message().contains("不安全"));
    }

    @Test
    public void sameVersionExternalRuleChangeIsUnsafeAndRetryNeverOverwritesIt() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> externalRules =
                externalValidRules(fixture.gateway.currentRules());
        fixture.gateway.replaceRulesAfterNextWrite(externalRules);

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.CURRENT_STATE_UNSAFE, model.state());
        assertFalse(model.canRetry());
        assertFalse(model.canRollback());
        assertFalse(model.canRedetect());
        int writesBeforeRetry = fixture.gateway.writeCount();

        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
        assertSameRules(externalRules, fixture.gateway.currentRules());
    }

    @Test
    public void unknownFieldsFirstFailureCanRollbackAndRestoresWriteGate() {
        assertUnsafeStructureFirstFailureCanRollback(true);
    }

    @Test
    public void unexpressibleDslFirstFailureCanRollbackAndRestoresWriteGate() {
        assertUnsafeStructureFirstFailureCanRollback(false);
    }

    @Test
    public void externalTargetChangeIsUnsafeAndNeverOverwritesAuthorization() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.changeTargetAuthorizationAfterNextWrite(
                "demo-target-a", false);

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.CURRENT_STATE_UNSAFE, model.state());
        int writesBeforeRetry = fixture.gateway.writeCount();
        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
        assertFalse(targetAuthorized(fixture.gateway, "demo-target-a"));
    }

    @Test
    public void externalPermissionChangeIsUnsafeAndNeverOverwritesAccess() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.changeNotificationAccessOnNextWrite(
                NotificationAccessState.MISSING);

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.CURRENT_STATE_UNSAFE, model.state());
        int writesBeforeRetry = fixture.gateway.writeCount();
        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
        assertEquals(
                NotificationAccessState.MISSING,
                fixture.gateway.snapshot().flowSnapshot().notificationAccess());
    }

    @Test
    public void retrySuccessUsesCurrentCompatibilitySnapshot() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());
        setCapabilityVersion(fixture.gateway, "demo-version-unknown");

        RuleCreationResult retried =
                fixture.controller.retry().value().orElseThrow();

        assertTrue(retried.compatibilityUnverified());
        RuleRecoveryModel model = fixture.controller.model();
        assertTrue(model.compatibilityUnverified());
        assertTrue(model.message().contains("兼容性未经完整验收"));
    }

    @Test
    public void verifiedRollbackRestoresOriginalStateExplainsFailureAndDoesNotReplay() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());

        ServiceResult<RuleRecoveryModel> rollback = fixture.controller.rollback();

        assertTrue(rollback.isSuccess());
        assertEquals(RuleRecoveryModel.State.ROLLED_BACK, rollback.value().orElseThrow().state());
        assertTrue(rollback.value().orElseThrow().failureReason().contains("校验失败"));
        assertSameRules(original, fixture.gateway.currentRules());
        assertEquals(2, fixture.gateway.writeCount());
        assertFalse(fixture.controller.model().debugModeEnabled());
    }

    @Test
    public void unverifiableRollbackEntersPersistentFaultWithOnlyRecoveryActions() {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original = new ArrayList<>(fixture.gateway.currentRules());
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());
        fixture.gateway.failAllReads();

        ServiceResult<RuleRecoveryModel> rollback = fixture.controller.rollback();

        assertFalse(rollback.isSuccess());
        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT, model.state());
        assertTrue(model.canRedetect());
        assertTrue(model.canRetryRollback());
        assertFalse(model.canRetry());
        assertFalse(model.canRollback());
        assertFalse(model.message().contains("强制写入"));

        int readsBeforeRedetect = fixture.gateway.readCount();
        assertTrue(fixture.controller.redetect().isSuccess());
        assertTrue(fixture.gateway.readCount() > readsBeforeRedetect);

        fixture.gateway.clearReadFailures();
        ServiceResult<RuleRecoveryModel> retriedRollback =
                fixture.controller.retryRollback();
        assertTrue(retriedRollback.isSuccess());
        assertEquals(
                RuleRecoveryModel.State.ROLLED_BACK,
                retriedRollback.value().orElseThrow().state());
        assertSameRules(original, fixture.gateway.currentRules());
        assertTrue(fixture.gateway.readOnlyFaultMessage().isEmpty());
    }

    @Test
    public void debugModeSkipsRetryPrevalidationButNotMandatorySafetyGates() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());
        fixture.gateway.replaceSnapshot(
                fixture.gateway.snapshot().withRules(externalValidRules(fixture.gateway.currentRules())));
        assertFalse(fixture.controller.model().canRetry());

        fixture.controller.setDebugMode(true);
        assertTrue(fixture.controller.model().canRetry());
        assertTrue(fixture.controller.retry().isSuccess());
        assertFalse(fixture.controller.model().debugModeEnabled());

        RuleStoreSnapshot incompleteCapabilities =
                new RuleStoreSnapshot(
                        fixture.gateway.snapshot().flowSnapshot(),
                        SOURCE,
                        CapabilityEvidence.demoComplete()
                                .without(CapabilityKind.METHOD, "rule.store.write"),
                        false,
                        false);
        fixture.gateway.replaceSnapshot(incompleteCapabilities);
        fixture.controller.setDebugMode(true);
        assertFalse(fixture.controller.model().canRetry());
        int writesBeforeRetry = fixture.gateway.writeCount();
        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
    }

    @Test
    public void debugModeNeverBypassesUnknownFieldWriteBan() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());
        fixture.gateway.replaceSnapshot(
                fixture.gateway.snapshot().withSafetyFlags(true, false));
        fixture.controller.setDebugMode(true);
        int writesBeforeRetry = fixture.gateway.writeCount();

        assertFalse(fixture.controller.model().canRetry());
        assertFalse(fixture.controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, fixture.gateway.writeCount());
    }

    @Test
    public void debugModeTurnsOffAfterRollbackPageExitAndProcessRestart() {
        Fixture fixture = fixture("demo-version-tested");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());

        fixture.controller.setDebugMode(true);
        fixture.controller.onPageExit();
        assertFalse(fixture.controller.model().debugModeEnabled());

        fixture.controller.setDebugMode(true);
        fixture.controller.onProcessRestart();
        assertFalse(fixture.controller.model().debugModeEnabled());

        fixture.controller.setDebugMode(true);
        assertTrue(fixture.controller.rollback().isSuccess());
        assertFalse(fixture.controller.model().debugModeEnabled());
    }

    @Test
    public void unknownVersionRecoveryViewKeepsCompatibilityWarning() {
        Fixture fixture = fixture("demo-version-unknown");
        fixture.gateway.corruptNextWrite();
        assertFalse(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());

        RuleRecoveryModel model = fixture.controller.model();

        assertTrue(model.compatibilityUnverified());
        assertTrue(model.compatibilityWarning().contains("兼容性未经完整验收"));
        assertTrue(model.message().contains("兼容性未经完整验收"));
        assertTrue(model.debugWarning().contains("高风险"));
    }

    @Test
    public void unknownVersionSuccessfulWriteViewKeepsCompatibilityWarning() {
        Fixture fixture = fixture("demo-version-unknown");

        assertTrue(fixture.controller.saveStructuredRule(fixture.selection, fixture.draft).isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertTrue(model.compatibilityUnverified());
        assertTrue(model.message().contains("兼容性未经完整验收"));
    }

    @Test
    public void unknownVersionUnsafeRecoveryViewKeepsCompatibilityWarning() {
        Fixture fixture = fixture("demo-version-unknown");
        fixture.gateway.replaceRulesAfterNextWrite(
                externalValidRules(fixture.gateway.currentRules()));

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());

        RuleRecoveryModel model = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.CURRENT_STATE_UNSAFE, model.state());
        assertTrue(model.compatibilityUnverified());
        assertTrue(model.message().contains("兼容性未经完整验收"));
    }

    @Test
    public void debugSwitchDefaultsOffAndHighRiskWarningIsAlwaysAvailable() {
        Fixture fixture = fixture("demo-version-tested");

        RuleRecoveryModel model = fixture.controller.model();

        assertFalse(model.debugModeEnabled());
        assertTrue(model.debugWarning().contains("高风险"));
        assertTrue(model.debugWarning().contains("不会绕过能力探测"));
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
        return new Fixture(gateway, lkgStore, controller, selection, draft);
    }

    private static List<NotificationRule> externalValidRules(List<NotificationRule> rules) {
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

    private static void assertUnsafeStructureFirstFailureCanRollback(
            boolean unknownFields) {
        Fixture fixture = fixture("demo-version-tested");
        List<NotificationRule> original =
                new ArrayList<>(fixture.gateway.currentRules());
        if (unknownFields) {
            fixture.gateway.markUnknownFieldsOnNextWrite();
        } else {
            fixture.gateway.markUnexpressibleDslOnNextWrite();
        }

        assertFalse(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());

        RuleRecoveryModel failed = fixture.controller.model();
        assertEquals(RuleRecoveryModel.State.AWAITING_DECISION, failed.state());
        assertFalse(failed.canRetry());
        assertTrue(failed.canRollback());
        assertEquals(1, fixture.lkgStore.saveCount());
        assertSameRules(original, fixture.lkgStore.load().rules());

        assertTrue(fixture.controller.rollback().isSuccess());
        assertEquals(
                RuleRecoveryModel.State.ROLLED_BACK,
                fixture.controller.model().state());
        assertSameRules(original, fixture.gateway.currentRules());
        assertFalse(fixture.controller.hasPendingRecovery());
        assertTrue(
                fixture.controller
                        .saveStructuredRule(fixture.selection, fixture.draft)
                        .isSuccess());
    }

    private static void setCapabilityVersion(
            FakeRuleStoreGateway gateway, String version) {
        gateway.replaceSnapshot(
                gateway.snapshot()
                        .withCapabilities(
                                CapabilityEvidence.demoComplete(
                                        version, Set.of("demo-version-tested"))));
    }

    private static boolean targetAuthorized(
            FakeRuleStoreGateway gateway, String targetDeviceId) {
        for (TargetDevice target : gateway.snapshot().flowSnapshot().targets()) {
            if (target.deviceId().equals(targetDeviceId)) {
                return target.targetAuthorized();
            }
        }
        throw new AssertionError("Missing target " + targetDeviceId);
    }

    private static void assertSameRules(
            List<NotificationRule> expected, List<NotificationRule> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            NotificationRule left = expected.get(index);
            NotificationRule right = actual.get(index);
            assertEquals(left.ruleId(), right.ruleId());
            assertEquals(left.sourceDeviceId(), right.sourceDeviceId());
            assertEquals(left.sourceDeviceName(), right.sourceDeviceName());
            assertEquals(left.targetDeviceId(), right.targetDeviceId());
            assertEquals(left.targetDeviceName(), right.targetDeviceName());
            assertEquals(left.status(), right.status());
            assertEquals(left.summary(), right.summary());
            assertEquals(left.dslJson(), right.dslJson());
            assertEquals(left.createdAt(), right.createdAt());
        }
    }

    private static final class Fixture {
        private final FakeRuleStoreGateway gateway;
        private final FakeLastKnownGoodStore lkgStore;
        private final RuleWriteRecoveryController controller;
        private final ConfirmedTargetSelection selection;
        private final StructuredRuleDraft draft;

        private Fixture(
                FakeRuleStoreGateway gateway,
                FakeLastKnownGoodStore lkgStore,
                RuleWriteRecoveryController controller,
                ConfirmedTargetSelection selection,
                StructuredRuleDraft draft) {
            this.gateway = gateway;
            this.lkgStore = lkgStore;
            this.controller = controller;
            this.selection = selection;
            this.draft = draft;
        }
    }
}
