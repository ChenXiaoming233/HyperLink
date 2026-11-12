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
import org.junit.Test;

public final class RuleEditorInteractionPolicyTest {
    @Test
    public void readonlyAndWritableAppDecisionsUseTheSamePolicyAsThePanel() {
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.IM),
                        List.of("demo.app.chat", "demo.app.social"),
                        100L);
        AppConditionEditorModel editor = AppConditionEditorModel.firstEditable(draft);
        RuleEditorInteractionPolicy policy =
                new RuleEditorInteractionPolicy(readiness(true));

        assertFalse(policy.appCheckboxEnabled(true));
        assertFalse(policy.appCheckboxEnabled(false));
        RuleEditorInteractionPolicy.AppSelection rejected =
                policy.selectApp(editor, "demo.app.chat");
        assertFalse(rejected.accepted());
        assertEquals(editor, rejected.editor());
        assertEquals(
                List.of("demo.app.chat", "demo.app.social"),
                rejected.editor().selectedPackages());
        assertTrue(rejected.message().contains("只读"));
        assertFalse(policy.saveAllowed());

        policy.update(readiness(false));

        assertTrue(policy.appCheckboxEnabled(true));
        assertFalse(policy.appCheckboxEnabled(false));
        RuleEditorInteractionPolicy.AppSelection accepted =
                policy.selectApp(editor, "demo.app.chat");
        assertTrue(accepted.accepted());
        assertEquals(List.of("demo.app.social"), accepted.editor().selectedPackages());
        assertTrue(policy.saveAllowed());
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
