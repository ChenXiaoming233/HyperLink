package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.DeviceType;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowService;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FakeNotificationFlowService implements NotificationFlowService {
    private NotificationFlowSnapshot snapshot;
    private final String failureMessage;
    private int readCount;

    public FakeNotificationFlowService(ConnectionState connectionState) {
        this(demoSnapshot(connectionState));
    }

    public FakeNotificationFlowService(NotificationFlowSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.failureMessage = "";
    }

    private FakeNotificationFlowService(String failureMessage) {
        this.snapshot = null;
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    public static FakeNotificationFlowService failing(String safeUserMessage) {
        return new FakeNotificationFlowService(safeUserMessage);
    }

    @Override
    public synchronized ServiceResult<NotificationFlowSnapshot> readSnapshot() {
        readCount++;
        if (snapshot == null) {
            return ServiceResult.failure(failureMessage);
        }
        return ServiceResult.success(snapshot);
    }

    public synchronized void setConnectionState(ConnectionState connectionState) {
        ConnectionState nextState = Objects.requireNonNull(connectionState, "connectionState");
        if (snapshot != null && nextState != snapshot.connectionState()) {
            snapshot =
                    snapshot.withConnectionState(nextState, snapshot.stateVersion() + 1L);
        }
    }

    public synchronized int readCount() {
        return readCount;
    }

    public static NotificationFlowSnapshot demoSnapshot(ConnectionState connectionState) {
        List<TargetDevice> targets =
                Arrays.asList(
                        new TargetDevice(
                                "demo-target-a",
                                "Demo-PC",
                                DeviceType.PC,
                                true,
                                true,
                                true,
                                true),
                        new TargetDevice(
                                "demo-target-b",
                                "Demo-Mac",
                                DeviceType.MAC,
                                false,
                                false,
                                true,
                                true),
                        new TargetDevice(
                                "demo-target-c",
                                "Demo-Pad",
                                DeviceType.PAD,
                                true,
                                true,
                                false,
                                true),
                        new TargetDevice(
                                "demo-target-d",
                                "Demo-Mirror-PC",
                                DeviceType.PC,
                                true,
                                true,
                                true,
                                false),
                        new TargetDevice(
                                "demo-target-e",
                                "Demo-Phone",
                                DeviceType.PHONE,
                                true,
                                true,
                                true,
                                true));
        List<NotificationRule> rules =
                Arrays.asList(
                        new NotificationRule(
                                "demo-rule-1",
                                "demo-source-phone",
                                "Demo-Phone",
                                "demo-target-a",
                                "Demo-PC",
                                "active",
                                "已开启通知转发到电脑 Demo-PC：演示消息、焦点通知或验证码。",
                                """
                                {
                                  "andGroups": [
                                    {
                                      "branches": [
                                        [{"field": "app", "op": "in", "value": "[\\\"demo.app.chat\\\",\\\"demo.app.social\\\",\\\"demo.app.retired\\\"]"}],
                                        [{"field": "isIsland", "op": "is", "value": "true"}],
                                        [{"field": "isVerifyCode", "op": "is", "value": "true"}]
                                      ]
                                    }
                                  ],
                                  "ruleText": "已开启通知转发到电脑 Demo-PC：演示消息、焦点通知或验证码。"
                                }
                                """,
                                1_700_000_000_001L),
                        new NotificationRule(
                                "demo-rule-2",
                                "demo-source-phone",
                                "Demo-Phone",
                                "demo-target-b",
                                "Demo-Mac",
                                "paused",
                                "已暂停转发到 Mac Demo-Mac：验证码。",
                                """
                                {
                                  "andGroups": [
                                    {
                                      "branches": [
                                        [{"field": "isVerifyCode", "op": "is", "value": "true"}]
                                      ]
                                    }
                                  ],
                                  "ruleText": "已暂停转发到 Mac Demo-Mac：验证码。"
                                }
                                """,
                                1_700_000_000_002L),
                        new NotificationRule(
                                "demo-rule-3",
                                "demo-source-phone",
                                "Demo-Phone",
                                "demo-target-c",
                                "Demo-Pad",
                                "active",
                                "已开启通知转发到平板 Demo-Pad：所有符合条件的通知。",
                                """
                                {
                                  "ruleText": "已开启通知转发到平板 Demo-Pad：所有符合条件的通知。"
                                }
                                """,
                                1_700_000_000_003L));
        return new NotificationFlowSnapshot(
                connectionState,
                1L,
                connectionState == ConnectionState.COMPATIBILITY_UNVERIFIED,
                targets,
                rules,
                NotificationAccessState.GRANTED,
                false);
    }

    public static NotificationFlowSnapshot staleDemoSnapshot(ConnectionState connectionState) {
        return demoSnapshot(connectionState).withStaleCache(true);
    }

    public static NotificationFlowSnapshot emptySnapshot(ConnectionState connectionState) {
        return new NotificationFlowSnapshot(
                connectionState,
                1L,
                connectionState == ConnectionState.COMPATIBILITY_UNVERIFIED,
                Collections.emptyList(),
                Collections.emptyList(),
                NotificationAccessState.GRANTED,
                false);
    }
}
