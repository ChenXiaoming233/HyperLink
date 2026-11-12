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
import java.util.List;
import org.junit.Test;

public final class SafeNotificationRuleWriterLocalFaultTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void persistenceExceptionStillTripsWriterLocalFaultGate() {
        NotificationFlowSnapshot flow =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        FakeRuleStoreGateway delegate =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                flow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                true,
                                false));
        ThrowingFaultGateway gateway = new ThrowingFaultGateway(delegate);
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        ConfirmedTargetSelection selection =
                new TargetSelectionFlow(flow)
                        .select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();

        assertFalse(writer.createAllNotificationsRule(selection).isSuccess());
        assertTrue(writer.isReadOnlyFault());
        assertTrue(writer.readOnlyFaultMessage().contains("未知字段"));

        RuleStoreSnapshot valid =
                new RuleStoreSnapshot(
                        flow,
                        SOURCE,
                        CapabilityEvidence.demoComplete(),
                        false,
                        false);
        delegate.replaceSnapshot(valid);
        int writesBefore = delegate.writeCount();
        assertFalse(writer.createAllNotificationsRule(selection).isSuccess());
        assertEquals(writesBefore, delegate.writeCount());
    }

    @Test
    public void localFaultSurvivesRedetectAndRetryWriteStillDoesNotWrite() {
        NotificationFlowSnapshot flow =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        FakeRuleStoreGateway delegate =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                flow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        ThrowingFaultGateway gateway = new ThrowingFaultGateway(delegate);
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(
                        gateway, new FakeLastKnownGoodStore());
        RuleWriteRecoveryController controller =
                new RuleWriteRecoveryController(writer);
        ConfirmedTargetSelection selection =
                new TargetSelectionFlow(flow)
                        .select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 300L);
        delegate.corruptNextWrite();
        assertFalse(controller.saveStructuredRule(selection, draft).isSuccess());
        assertEquals(
                RuleRecoveryModel.State.AWAITING_DECISION,
                controller.model().state());
        try {
            writer.enterReadOnlyFault("本地故障闸门测试。");
        } catch (IllegalStateException expected) {
            // The throwing gateway models a failed persistence attempt.
        }
        assertTrue(writer.isReadOnlyFault());
        int writesBeforeRetry = delegate.writeCount();

        controller.redetect();

        assertEquals(
                RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT,
                controller.model().state());
        assertFalse(controller.retry().isSuccess());
        assertEquals(writesBeforeRetry, delegate.writeCount());
    }

    private static final class ThrowingFaultGateway implements RuleStoreGateway {
        private final FakeRuleStoreGateway delegate;

        private ThrowingFaultGateway(FakeRuleStoreGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public ServiceResult<RuleStoreSnapshot> read() {
            return delegate.read();
        }

        @Override
        public ServiceResult<RuleWriteReceipt> write(
                List<NotificationRule> rules,
                long expectedStoreVersion,
                List<NotificationRule> expectedRules) {
            return delegate.write(rules, expectedStoreVersion, expectedRules);
        }

        @Override
        public ServiceResult<RuleWriteReceipt> restoreRules(
                RuleLkg lkg,
                long expectedStoreVersion,
                List<NotificationRule> expectedRules) {
            return delegate.restoreRules(lkg, expectedStoreVersion, expectedRules);
        }

        @Override
        public ServiceResult<RuleWriteReceipt> restoreRulesForVerifiedRecovery(
                RuleLkg lkg,
                long expectedStoreVersion,
                List<NotificationRule> expectedRules) {
            return delegate.restoreRulesForVerifiedRecovery(
                    lkg, expectedStoreVersion, expectedRules);
        }

        @Override
        public String readOnlyFaultMessage() {
            return "";
        }

        @Override
        public void enterReadOnlyFault(String message) {
            throw new IllegalStateException("演示故障持久化异常。");
        }
    }
}
