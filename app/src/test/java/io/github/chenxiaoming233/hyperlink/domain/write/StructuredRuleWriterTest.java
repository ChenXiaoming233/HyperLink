package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleAndGroup;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleBranch;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class StructuredRuleWriterTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void updatesExistingRuleWithoutChangingIdStatusOrCreatedAt() {
        Fixture fixture = fixture(FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED));
        NotificationRule existing = fixture.gateway.currentRules().get(1);
        StructuredRuleDraft parsed = StructuredRuleDsl.parse(existing);
        RuleBranch changedBranch =
                RuleBranch.of(
                        RuleCondition.appIn(
                                List.of("demo.app.chat", "demo.app.social")),
                        RuleCondition.isIsland(true));
        StructuredRuleDraft changed =
                parsed.withStatus("active")
                        .replaceGroup(0, RuleAndGroup.of(changedBranch));

        RuleCreationResult result =
                fixture.writer
                        .saveStructuredRule(confirmed(fixture, existing.targetDeviceId()), changed)
                        .value()
                        .orElseThrow();

        NotificationRule saved =
                fixture.gateway.currentRules().stream()
                        .filter(rule -> rule.ruleId().equals(existing.ruleId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(3, fixture.gateway.currentRules().size());
        assertEquals(existing.ruleId(), saved.ruleId());
        assertEquals("paused", saved.status());
        assertEquals(existing.createdAt(), saved.createdAt());
        assertEquals(changed.summary(), saved.summary());
        assertTrue(saved.dslJson().contains("\"branches\""));
        assertFalse(saved.dslJson().contains("presetType"));
        assertEquals(1, matchingRuleCount(fixture.gateway.currentRules(), existing.targetDeviceId()));
        assertEquals(result.rule().ruleId(), saved.ruleId());
    }

    @Test
    public void createsStructuredRuleFromMultipleOrPresetBranches() {
        NotificationFlowSnapshot flow =
                withTarget(
                        FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED),
                        new TargetDevice(
                                "demo-target-new",
                                "Demo-New-PC",
                                DeviceType.PC,
                                true,
                                true,
                                true,
                                true));
        Fixture fixture = fixture(flow);
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(
                                RulePreset.IM,
                                RulePreset.FOCUSED_NOTIFICATION,
                                RulePreset.VERIFICATION_CODE),
                        List.of("demo.app.chat", "demo.app.social"),
                        987654321L);

        RuleCreationResult first =
                fixture.writer
                        .saveStructuredRule(confirmed(fixture, "demo-target-new"), draft)
                        .value()
                        .orElseThrow();
        RuleCreationResult second =
                fixture.writer
                        .saveStructuredRule(confirmed(fixture, "demo-target-new"), draft)
                        .value()
                        .orElseThrow();

        assertEquals(4, fixture.gateway.currentRules().size());
        assertEquals(first.rule().ruleId(), second.rule().ruleId());
        assertEquals(987654321L, first.rule().createdAt());
        assertEquals(draft.summary(), first.rule().summary());
        assertEquals(
                3,
                occurrences(first.rule().dslJson(), "{\"field\""));
        assertEquals(
                1,
                matchingRuleCount(fixture.gateway.currentRules(), "demo-target-new"));
    }

    private static Fixture fixture(NotificationFlowSnapshot flow) {
        RuleStoreSnapshot snapshot =
                new RuleStoreSnapshot(
                        flow,
                        SOURCE,
                        CapabilityEvidence.demoComplete(),
                        false,
                        false);
        FakeRuleStoreGateway gateway = new FakeRuleStoreGateway(snapshot);
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        return new Fixture(gateway, writer, new TargetSelectionFlow(flow));
    }

    private static ConfirmedTargetSelection confirmed(Fixture fixture, String targetId) {
        return fixture.flow.select(SOURCE, targetId)
                .value()
                .orElseThrow()
                .confirmAuthorization();
    }

    private static NotificationFlowSnapshot withTarget(
            NotificationFlowSnapshot snapshot, TargetDevice target) {
        List<TargetDevice> targets = new ArrayList<>(snapshot.targets());
        targets.add(target);
        return new NotificationFlowSnapshot(
                snapshot.connectionState(),
                snapshot.stateVersion() + 1L,
                snapshot.compatibilityUnverified(),
                targets,
                snapshot.rules(),
                snapshot.notificationAccess(),
                snapshot.staleCache());
    }

    private static int matchingRuleCount(List<NotificationRule> rules, String targetId) {
        int count = 0;
        for (NotificationRule rule : rules) {
            if (rule.sourceDeviceId().equals(SOURCE.deviceId())
                    && rule.targetDeviceId().equals(targetId)) {
                count++;
            }
        }
        return count;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static final class Fixture {
        private final FakeRuleStoreGateway gateway;
        private final SafeNotificationRuleWriter writer;
        private final TargetSelectionFlow flow;

        private Fixture(
                FakeRuleStoreGateway gateway,
                SafeNotificationRuleWriter writer,
                TargetSelectionFlow flow) {
            this.gateway = gateway;
            this.writer = writer;
            this.flow = flow;
        }
    }
}
