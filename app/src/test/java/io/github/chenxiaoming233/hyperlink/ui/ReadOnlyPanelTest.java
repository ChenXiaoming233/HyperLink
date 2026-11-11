package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowService;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

public final class ReadOnlyPanelTest {
    @Test
    public void displaysOnlyReceivablePcPadAndMacTargetsWithAllStates() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);

        PanelDisplayModel model = PanelStateMapper.map(snapshot);

        assertEquals(3, model.targets().size());
        PanelDisplayModel.TargetItem pc = target(model.targets(), "Demo-PC");
        assertEquals("PC", pc.typeLabel());
        assertEquals("在线", pc.onlineLabel());
        assertEquals("可达", pc.reachableLabel());
        assertEquals("目标授权：已授权", pc.authorizationLabel());
        assertEquals("接收能力：支持", pc.receiveCapabilityLabel());
        assertFalse(model.targets().stream()
                .anyMatch(target -> target.displayName().equals("Demo-Phone")));
        assertFalse(model.targets().stream()
                .anyMatch(target -> target.displayName().equals("Demo-Mirror-PC")));
    }

    @Test
    public void preservesRuleCountOrderIdStatusTargetDslAndSummary() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);

        PanelDisplayModel model = PanelStateMapper.map(snapshot);

        assertEquals(3, model.rules().size());
        assertEquals(snapshot.rules().size(), model.rules().size());
        for (int index = 0; index < snapshot.rules().size(); index++) {
            NotificationRule sourceRule = snapshot.rules().get(index);
            PanelDisplayModel.RuleItem displayedRule = model.rules().get(index);

            assertEquals(index + 1, displayedRule.position());
            assertEquals(sourceRule.ruleId(), displayedRule.ruleId());
            assertEquals(sourceRule.sourceDeviceId(), displayedRule.sourceDeviceId());
            assertEquals(sourceRule.sourceDeviceName(), displayedRule.sourceDeviceName());
            assertEquals(sourceRule.targetDeviceId(), displayedRule.targetDeviceId());
            assertEquals(sourceRule.targetDeviceName(), displayedRule.targetDeviceName());
            assertEquals(sourceRule.status(), displayedRule.storedStatus());
            assertEquals(sourceRule.summary(), displayedRule.summary());
            assertEquals(sourceRule.dslJson(), displayedRule.dslJson());
        }
        assertEquals("demo-rule-1", model.rules().get(0).ruleId());
        assertEquals("demo-rule-2", model.rules().get(1).ruleId());
        assertEquals("已暂停", model.rules().get(1).statusLabel());
    }

    @Test
    public void distinguishesTargetAuthorizationFromSystemNotificationAccess() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        PanelDisplayModel grantedModel = PanelStateMapper.map(snapshot);
        PanelDisplayModel missingModel =
                PanelStateMapper.map(
                        snapshot.withNotificationAccess(NotificationAccessState.MISSING));
        PanelDisplayModel revokedModel =
                PanelStateMapper.map(
                        snapshot.withNotificationAccess(NotificationAccessState.REVOKED));

        assertEquals("目标授权：已授权", target(grantedModel.targets(), "Demo-PC").authorizationLabel());
        assertEquals("目标授权：未授权", target(grantedModel.targets(), "Demo-Pad").authorizationLabel());
        assertTrue(grantedModel.notificationAccessText().contains("系统通知使用权"));
        assertTrue(grantedModel.notificationAccessText().contains("已授予"));
        assertFalse(grantedModel.notificationAccessBlocked());

        assertTrue(missingModel.notificationAccessBlocked());
        assertTrue(missingModel.notificationAccessText().contains("缺失"));
        assertTrue(activeRule(missingModel.rules(), "demo-rule-1").statusLabel().contains("受阻"));
        assertEquals("已暂停", activeRule(missingModel.rules(), "demo-rule-2").statusLabel());

        assertTrue(revokedModel.notificationAccessBlocked());
        assertTrue(revokedModel.notificationAccessText().contains("已撤销"));
        assertTrue(activeRule(revokedModel.rules(), "demo-rule-1").statusLabel().contains("受阻"));
    }

    @Test
    public void keepsOfflineExistingTargetAndWarnsAboutNoQueueOrRetry() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);

        PanelDisplayModel model = PanelStateMapper.map(snapshot);
        PanelDisplayModel.TargetItem mac = target(model.targets(), "Demo-Mac");

        assertEquals("离线", mac.onlineLabel());
        assertEquals("不可达", mac.reachableLabel());
        assertTrue(mac.notice().contains("不会排队"));
        assertTrue(mac.notice().contains("不会自动重试"));
        assertTrue(mac.notice().contains("当前离线"));
        assertEquals("Demo-Mac", activeRule(model.rules(), "demo-rule-2").targetDeviceName());
        assertTrue(activeRule(model.rules(), "demo-rule-2").notice().contains("当前离线"));
    }

    @Test
    public void onlineButUnreachableIsNotReportedAsOffline() {
        TargetDevice target =
                new TargetDevice(
                        "demo-target-online",
                        "Demo-Online",
                        DeviceType.PC,
                        true,
                        false,
                        true,
                        true);
        NotificationRule rule =
                rule("demo-rule-online", "demo-target-online", "Demo-Online");

        PanelDisplayModel model =
                PanelStateMapper.map(
                        new NotificationFlowSnapshot(
                                ConnectionState.CONNECTED,
                                1L,
                                false,
                                List.of(target),
                                List.of(rule),
                                NotificationAccessState.GRANTED,
                                false));

        PanelDisplayModel.TargetItem displayedTarget = model.targets().get(0);
        PanelDisplayModel.RuleItem displayedRule = model.rules().get(0);
        assertEquals("在线", displayedTarget.onlineLabel());
        assertEquals("不可达", displayedTarget.reachableLabel());
        assertTrue(displayedTarget.notice().contains("在线但当前不可达"));
        assertFalse(displayedTarget.notice().contains("当前离线"));
        assertTrue(displayedRule.notice().contains("在线但当前不可达"));
        assertFalse(displayedRule.notice().contains("当前离线"));
    }

    @Test
    public void activeRulesAreBlockedWhenTargetsAreMissingOrCannotReceive() {
        NotificationRule missingRule =
                rule("demo-rule-missing", "demo-target-missing", "Demo-Missing");
        NotificationRule noReceiveRule =
                rule("demo-rule-no-receive", "demo-target-no-receive", "Demo-No-Receive");
        NotificationRule phoneRule =
                rule("demo-rule-phone", "demo-target-phone", "Demo-Phone");
        TargetDevice noReceive =
                new TargetDevice(
                        "demo-target-no-receive",
                        "Demo-No-Receive",
                        DeviceType.PC,
                        true,
                        true,
                        true,
                        false);
        TargetDevice phone =
                new TargetDevice(
                        "demo-target-phone",
                        "Demo-Phone",
                        DeviceType.PHONE,
                        true,
                        true,
                        true,
                        true);

        PanelDisplayModel missing =
                PanelStateMapper.map(snapshot(List.of(), List.of(missingRule)));
        PanelDisplayModel filtered =
                PanelStateMapper.map(snapshot(List.of(noReceive, phone), List.of(noReceiveRule, phoneRule)));

        assertFalse(missing.rules().get(0).statusLabel().contains("已启用"));
        assertTrue(missing.rules().get(0).statusLabel().contains("受阻"));
        assertTrue(filtered.rules().get(0).statusLabel().contains("受阻"));
        assertTrue(filtered.rules().get(1).statusLabel().contains("受阻"));
        assertTrue(filtered.targets().isEmpty());
    }

    @Test
    public void fakeFixtureUsesOnlySyntheticDeviceAndRuleIdentifiers() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        assertTrue(snapshot.targets().stream()
                .allMatch(target -> target.deviceId().startsWith("demo-target-")));
        assertTrue(snapshot.targets().stream()
                .allMatch(target -> target.displayName().startsWith("Demo-")));
        assertTrue(snapshot.rules().stream()
                .allMatch(rule -> rule.ruleId().startsWith("demo-rule-")));
        assertTrue(snapshot.rules().stream()
                .allMatch(rule -> rule.sourceDeviceId().startsWith("demo-source-")));
        assertTrue(snapshot.rules().stream()
                .allMatch(rule -> rule.targetDeviceId().startsWith("demo-target-")));
    }

    @Test
    public void pausedRulesSignalUnavailableTargetsWithoutBlockedConflict() {
        NotificationRule missingRule =
                pausedRule("demo-rule-paused-missing", "demo-target-missing", "Demo-Missing");
        NotificationRule noReceiveRule =
                pausedRule("demo-rule-paused-no-receive", "demo-target-no-receive", "Demo-No-Receive");
        TargetDevice noReceive =
                new TargetDevice(
                        "demo-target-no-receive",
                        "Demo-No-Receive",
                        DeviceType.PC,
                        true,
                        true,
                        true,
                        false);

        PanelDisplayModel missing =
                PanelStateMapper.map(snapshot(List.of(), List.of(missingRule)));
        PanelDisplayModel filtered =
                PanelStateMapper.map(snapshot(List.of(noReceive), List.of(noReceiveRule)));

        for (PanelDisplayModel.RuleItem rule :
                List.of(missing.rules().get(0), filtered.rules().get(0))) {
            assertEquals("已暂停（目标不可用，恢复前需处理）", rule.statusLabel());
            assertFalse(rule.notice().contains("此规则受阻"));
            assertTrue(rule.notice().contains("恢复前需处理"));
        }
    }

    @Test
    public void marksCachedSnapshotAsExpiredAndKeepsPanelReadOnly() {
        NotificationFlowSnapshot snapshot =
                FakeNotificationFlowService.staleDemoSnapshot(ConnectionState.TARGET_PROCESS_UNREACHABLE);

        PanelDisplayModel model = PanelStateMapper.map(snapshot);

        assertTrue(model.staleCacheVisible());
        assertTrue(model.message().contains("已过期"));
        assertTrue(model.message().contains("不能用于写入"));
        assertTrue(model.readOnly());
        for (Method method : NotificationFlowService.class.getDeclaredMethods()) {
            assertEquals("readSnapshot", method.getName());
        }
    }

    @Test
    public void failureAndEmptyDataUseSafeUserCopy() {
        PanelDisplayModel failure = PanelStateMapper.mapFailure("");
        NotificationFlowService failingService =
                FakeNotificationFlowService.failing("演示读取失败，未接触真实数据。");
        PanelDisplayModel empty =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(ConnectionState.CONNECTED));

        assertFalse(failure.title().isBlank());
        assertTrue(failure.message().contains("无法读取"));
        assertTrue(failure.readOnly());
        assertFalse(failingService.readSnapshot().isSuccess());
        assertTrue(failingService.readSnapshot().userMessage().contains("未接触真实数据"));

        assertTrue(empty.emptyStateVisible());
        assertTrue(empty.message().contains("暂无"));
        assertTrue(empty.message().contains("未读取真实"));
        assertTrue(empty.targets().isEmpty());
        assertTrue(empty.rules().isEmpty());
    }

    @Test
    public void unavailableStatesAreNotRewrittenAsEmptyData() {
        ConnectionState[] unavailable = {
            ConnectionState.NOT_ACTIVATED,
            ConnectionState.TARGET_PROCESS_UNREACHABLE,
            ConnectionState.CAPABILITY_INSUFFICIENT
        };

        for (ConnectionState state : unavailable) {
            PanelDisplayModel model =
                    PanelStateMapper.map(FakeNotificationFlowService.emptySnapshot(state));

            assertFalse(state.name(), model.emptyStateVisible());
            assertNotEquals(state.name(), "暂无演示数据", model.title());
            assertTrue(state.name(), model.targetSectionPlaceholder().contains("无法确定"));
            assertTrue(state.name(), model.ruleSectionPlaceholder().contains("无法确定"));
        }
    }

    @Test
    public void failureCopyFallsBackForNullAndWhitespace() {
        assertEquals("无法读取通知流转状态，请稍后重试。", PanelStateMapper.mapFailure(null).message());
        assertEquals("无法读取通知流转状态，请稍后重试。", PanelStateMapper.mapFailure("   ").message());
        assertEquals("无法读取通知流转状态，请稍后重试。", PanelStateMapper.mapFailure("\n\t").message());
    }

    @Test
    public void failureStateUsesUnknownListPlaceholdersAndBlockedAccess() {
        PanelDisplayModel failure = PanelStateMapper.mapFailure("演示读取失败。");

        assertTrue(failure.targetSectionPlaceholder().contains("无法确定"));
        assertTrue(failure.ruleSectionPlaceholder().contains("无法确定"));
        assertFalse(failure.targetSectionPlaceholder().contains("当前没有"));
        assertFalse(failure.ruleSectionPlaceholder().contains("没有演示规则"));
        assertFalse(failure.compatibilityBadgeVisible());
        assertFalse(failure.staleCacheVisible());
        assertTrue(failure.notificationAccessBlocked());
    }

    @Test
    public void successfulEmptySnapshotUsesDeterministicEmptyPlaceholders() {
        PanelDisplayModel empty =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(ConnectionState.CONNECTED));

        assertTrue(empty.emptyStateVisible());
        assertTrue(empty.targetSectionPlaceholder().contains("当前没有"));
        assertTrue(empty.ruleSectionPlaceholder().contains("当前快照没有"));
    }

    @Test
    public void staleEmptyCacheUsesExpiredCachePlaceholders() {
        PanelDisplayModel staleEmpty =
                PanelStateMapper.map(
                        FakeNotificationFlowService.emptySnapshot(
                                        ConnectionState.TARGET_PROCESS_UNREACHABLE)
                                .withStaleCache(true));

        assertTrue(staleEmpty.staleCacheVisible());
        assertTrue(staleEmpty.targetSectionPlaceholder().contains("缓存快照"));
        assertTrue(staleEmpty.ruleSectionPlaceholder().contains("缓存快照"));
        assertFalse(staleEmpty.targetSectionPlaceholder().contains("尚未成功读取"));
        assertFalse(staleEmpty.ruleSectionPlaceholder().contains("尚未成功读取"));
    }

    private static PanelDisplayModel.TargetItem target(
            List<PanelDisplayModel.TargetItem> targets, String displayName) {
        return targets.stream()
                .filter(target -> target.displayName().equals(displayName))
                .findFirst()
                .orElseThrow();
    }

    private static PanelDisplayModel.RuleItem activeRule(
            List<PanelDisplayModel.RuleItem> rules, String ruleId) {
        return rules.stream()
                .filter(rule -> rule.ruleId().equals(ruleId))
                .findFirst()
                .orElseThrow();
    }

    private static NotificationRule rule(String ruleId, String targetId, String targetName) {
        return rule(ruleId, targetId, targetName, "active");
    }

    private static NotificationRule pausedRule(
            String ruleId, String targetId, String targetName) {
        return rule(ruleId, targetId, targetName, "paused");
    }

    private static NotificationRule rule(
            String ruleId, String targetId, String targetName, String status) {
        return new NotificationRule(
                ruleId,
                "demo-source",
                "Demo-Source",
                targetId,
                targetName,
                status,
                "演示规则摘要",
                "{\"ruleText\":\"演示规则摘要\"}");
    }

    private static NotificationFlowSnapshot snapshot(
            List<TargetDevice> targets, List<NotificationRule> rules) {
        return new NotificationFlowSnapshot(
                ConnectionState.CONNECTED,
                1L,
                false,
                targets,
                rules,
                NotificationAccessState.GRANTED,
                false);
    }
}
