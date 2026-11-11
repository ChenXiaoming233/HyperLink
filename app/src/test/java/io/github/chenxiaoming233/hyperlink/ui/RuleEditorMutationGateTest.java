package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppConditionEditorModel;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleModelReadiness;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class RuleEditorMutationGateTest {
    @Test
    public void readonlyTransitionBlocksAppSelectionAndSaveActions() {
        RuleEditorMutationGate gate =
                new RuleEditorMutationGate(readiness(false));
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.IM),
                        List.of("demo.app.chat", "demo.app.social"),
                        100L);
        AtomicReference<AppConditionEditorModel> editor =
                new AtomicReference<>(AppConditionEditorModel.firstEditable(draft));

        assertTrue(
                gate.edit(
                        () ->
                                editor.set(
                                        editor.get()
                                                .togglePackage("demo.app.chat"))));
        assertEquals(List.of("demo.app.social"), editor.get().selectedPackages());

        gate.update(readiness(true));
        List<String> packagesBefore = editor.get().selectedPackages();

        assertFalse(
                gate.edit(
                        () ->
                                editor.set(
                                        editor.get()
                                                .togglePackage("demo.app.social"))));
        assertEquals(packagesBefore, editor.get().selectedPackages());

        AtomicBoolean saveCalled = new AtomicBoolean();
        assertFalse(gate.save(() -> saveCalled.set(true)));
        assertFalse(saveCalled.get());
        assertTrue(gate.reason().contains("未知字段"));
    }

    private static RuleModelReadiness readiness(boolean unknownFields) {
        NotificationFlowSnapshot flow =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        FakeRuleStoreGateway gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                flow,
                                new SourceDevice("demo-source-phone", "Demo-Phone"),
                                CapabilityEvidence.demoComplete(),
                                unknownFields,
                                false));
        return RuleModelReadiness.inspect(gateway);
    }
}
