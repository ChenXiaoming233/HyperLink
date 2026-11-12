package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class RuleModelReadinessTest {
    private static final SourceDevice SOURCE =
            new SourceDevice("demo-source-phone", "Demo-Phone");

    @Test
    public void unknownFieldInAnyRuleMakesTheWholeModelReadOnly() {
        FakeRuleStoreGateway gateway =
                gatewayWithRule(
                        "demo-rule-unknown-field",
                        "active",
                        "{\"ruleText\":\"演示规则\",\"futureField\":true}");

        RuleModelReadiness readiness = RuleModelReadiness.inspect(gateway);

        assertFalse(readiness.writable());
        assertTrue(readiness.reason().contains("demo-rule-unknown-field"));
        assertTrue(readiness.reason().contains("未知字段"));
        assertTrue(readiness.reason().contains("整份规则模型"));
    }

    @Test
    public void unknownStatusInAnyRuleMakesTheWholeModelReadOnly() {
        FakeRuleStoreGateway gateway =
                gatewayWithRule(
                        "demo-rule-unknown-status",
                        "blocked",
                        "{\"ruleText\":\"演示规则\"}");

        RuleModelReadiness readiness = RuleModelReadiness.inspect(gateway);

        assertFalse(readiness.writable());
        assertTrue(readiness.reason().contains("demo-rule-unknown-status"));
        assertTrue(readiness.reason().contains("未知状态"));
        assertTrue(readiness.reason().contains("blocked"));
    }

    @Test
    public void unexpressibleDslInAnyRuleMakesTheWholeModelReadOnly() {
        FakeRuleStoreGateway gateway =
                gatewayWithRule(
                        "demo-rule-unexpressible",
                        "active",
                        "{\"andGroups\":[{\"branches\":[["
                                + "{\"field\":\"app\",\"op\":\"contains\","
                                + "\"value\":\"demo.app\"}"
                                + "]]}],\"ruleText\":\"演示规则\"}");

        RuleModelReadiness readiness = RuleModelReadiness.inspect(gateway);

        assertFalse(readiness.writable());
        assertTrue(readiness.reason().contains("demo-rule-unexpressible"));
        assertTrue(readiness.reason().contains("不可表达"));
        assertTrue(readiness.reason().contains("整份规则模型"));
    }

    @Test
    public void invalidStoredRegexInAnyRuleMakesTheWholeModelReadOnly() {
        FakeRuleStoreGateway gateway =
                gatewayWithRule(
                        "demo-rule-invalid-regex",
                        "active",
                        "{\"andGroups\":[{\"branches\":[["
                                + "{\"field\":\"text\",\"op\":\"regex\",\"value\":\"[\"}"
                                + "]]}],\"ruleText\":\"演示规则\"}");

        RuleModelReadiness readiness = RuleModelReadiness.inspect(gateway);

        assertFalse(readiness.writable());
        assertTrue(readiness.reason().contains("demo-rule-invalid-regex"));
        assertTrue(readiness.reason().contains("正则语法无效"));
        assertTrue(readiness.reason().contains("整份规则模型"));
    }

    @Test
    public void redetectionRecoversOnlyAfterTheFakeSnapshotIsRepaired() {
        NotificationFlowSnapshot clean =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        FakeRuleStoreGateway gateway = gatewayWithRule(
                "demo-rule-repairable",
                "active",
                "{\"ruleText\":\"演示规则\",\"futureField\":true}");

        assertFalse(RuleModelReadiness.inspect(gateway).writable());

        gateway.replaceSnapshot(
                new RuleStoreSnapshot(
                        clean,
                        SOURCE,
                        CapabilityEvidence.demoComplete(),
                        false,
                        false));

        RuleModelReadiness redetected = RuleModelReadiness.inspect(gateway);

        assertTrue(redetected.writable());
        assertTrue(redetected.reason().isEmpty());
    }

    private static FakeRuleStoreGateway gatewayWithRule(
            String ruleId, String status, String dsl) {
        NotificationFlowSnapshot demo =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        List<NotificationRule> rules = new ArrayList<>(demo.rules());
        NotificationRule replacement =
                new NotificationRule(
                        ruleId,
                        SOURCE.deviceId(),
                        SOURCE.displayName(),
                        "demo-target-a",
                        "Demo-PC",
                        status,
                        "演示规则",
                        dsl,
                        42L);
        rules.set(0, replacement);
        NotificationFlowSnapshot changed =
                new NotificationFlowSnapshot(
                        demo.connectionState(),
                        demo.stateVersion() + 1L,
                        demo.compatibilityUnverified(),
                        demo.targets(),
                        rules,
                        demo.notificationAccess(),
                        demo.staleCache());
        return new FakeRuleStoreGateway(
                new RuleStoreSnapshot(
                        changed,
                        SOURCE,
                        CapabilityEvidence.demoComplete(),
                        false,
                        false));
    }
}
