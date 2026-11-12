package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteRecoveryController;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.List;
import org.junit.Test;

public final class RuleRecoveryDialogModelTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void firstFailureDialogRecommendsRollbackAndOffersOneRetryAndDefer() {
        RuleWriteRecoveryController controller = controllerWithFailure();

        RuleRecoveryDialogModel dialog =
                RuleRecoveryDialogModel.from(controller.model());

        assertEquals(RuleRecoveryDialogModel.Action.ROLLBACK, dialog.recommendedAction());
        assertTrue(dialog.title().contains("写入失败"));
        assertTrue(dialog.rollbackLabel().contains("推荐"));
        assertTrue(dialog.retryLabel().contains("一次"));
        assertTrue(dialog.deferLabel().contains("暂不操作"));
        assertTrue(dialog.retryEnabled());
        assertFalse(dialog.message().isEmpty());
    }

    private static RuleWriteRecoveryController controllerWithFailure() {
        var flow = FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        FakeRuleStoreGateway gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                flow,
                                SOURCE,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        RuleWriteRecoveryController controller =
                new RuleWriteRecoveryController(gateway, new FakeLastKnownGoodStore());
        ConfirmedTargetSelection selection =
                new TargetSelectionFlow(flow)
                        .select(SOURCE, "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 100L);
        gateway.corruptNextWrite();
        controller.saveStructuredRule(selection, draft);
        return controller;
    }
}
