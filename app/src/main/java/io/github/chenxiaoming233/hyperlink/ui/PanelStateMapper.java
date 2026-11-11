package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PanelStateMapper {
    private PanelStateMapper() {}

    public static PanelDisplayModel map(NotificationFlowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ConnectionState state = snapshot.connectionState();
        boolean accessBlocked = snapshot.notificationAccess() != NotificationAccessState.GRANTED;
        boolean successfulRead = isSuccessfulRead(state);

        String title;
        String message;
        switch (state) {
            case CONNECTED:
                title = "fake 快照已加载";
                message = "已从 fake gateway 读取演示快照；真实 VoiceAssist 适配器尚未接入。";
                break;
            case NOT_ACTIVATED:
                title = "模块尚未激活";
                message = "请在 LSPosed 中启用模块并重启目标应用；当前仅显示演示状态。";
                break;
            case TARGET_PROCESS_UNREACHABLE:
                title = "目标进程不可达";
                message = "演示服务无法访问 VoiceAssist 进程，未执行任何真实读取或写入。";
                break;
            case COMPATIBILITY_UNVERIFIED:
                title = "兼容性未经完整验收";
                message = "只读演示数据可查看；当前没有连接真实 VoiceAssist，也未完成设备验收。";
                break;
            case CAPABILITY_INSUFFICIENT:
                title = "能力不足";
                message = "演示服务报告关键能力不足，当前面板保持只读。";
                break;
            default:
                throw new IllegalArgumentException("Unhandled connection state: " + state);
        }

        List<PanelDisplayModel.TargetItem> targets = mapTargets(snapshot.targets());
        List<PanelDisplayModel.RuleItem> rules =
                mapRules(snapshot.rules(), snapshot.targets(), snapshot.notificationAccess());
        boolean emptyStateVisible =
                successfulRead
                        && !snapshot.staleCache()
                        && targets.isEmpty()
                        && rules.isEmpty();
        String targetSectionPlaceholder =
                targetSectionPlaceholder(state, snapshot.staleCache(), targets.isEmpty());
        String ruleSectionPlaceholder =
                ruleSectionPlaceholder(state, snapshot.staleCache(), rules.isEmpty());

        if (snapshot.staleCache()) {
            title = "缓存快照已过期";
            message =
                    "缓存快照已过期：这是最近一次成功读取的历史只读副本，"
                            + "不代表当前状态，不能用于写入。"
                            + message;
        } else if (emptyStateVisible) {
            title = "暂无演示数据";
            message =
                    "暂无演示数据：当前快照没有可展示的目标或规则。"
                            + "未读取真实 VoiceAssist 数据。";
        }

        boolean showCompatibility =
                snapshot.compatibilityUnverified()
                        || state == ConnectionState.COMPATIBILITY_UNVERIFIED;
        boolean retryEnabled =
                state != ConnectionState.CONNECTED
                        && state != ConnectionState.COMPATIBILITY_UNVERIFIED;
        return new PanelDisplayModel(
                title,
                message,
                retryEnabled,
                showCompatibility,
                snapshot.staleCache(),
                emptyStateVisible,
                targetSectionPlaceholder,
                ruleSectionPlaceholder,
                notificationAccessText(snapshot.notificationAccess()),
                accessBlocked,
                targets,
                rules);
    }

    /**
     * Maps only caller-sanitized UI copy. Never pass exception messages or stack traces here.
     */
    public static PanelDisplayModel mapFailure(String safeUserMessage) {
        String message =
                safeUserMessage == null || safeUserMessage.trim().isEmpty()
                        ? "无法读取通知流转状态，请稍后重试。"
                        : safeUserMessage;
        return new PanelDisplayModel(
                "读取失败",
                message,
                true,
                false,
                false,
                false,
                "无法确定当前目标：读取未成功。",
                "无法确定当前规则：读取未成功。",
                "系统通知使用权：未知（读取失败）。",
                true,
                Collections.emptyList(),
                Collections.emptyList());
    }

    private static List<PanelDisplayModel.TargetItem> mapTargets(List<TargetDevice> devices) {
        List<PanelDisplayModel.TargetItem> items = new ArrayList<>();
        for (TargetDevice device : devices) {
            if (!device.supportsNotificationReceive()) {
                continue;
            }
            items.add(
                    new PanelDisplayModel.TargetItem(
                            device.deviceId(),
                            device.displayName(),
                            device.type().name(),
                            device.online() ? "在线" : "离线",
                            device.reachable() ? "可达" : "不可达",
                            "目标授权：" + (device.targetAuthorized() ? "已授权" : "未授权"),
                            "接收能力：" + (device.receiveCapability() ? "支持" : "不支持"),
                            targetNotice(device)));
        }
        return items;
    }

    private static List<PanelDisplayModel.RuleItem> mapRules(
            List<NotificationRule> rules,
            List<TargetDevice> devices,
            NotificationAccessState accessState) {
        List<PanelDisplayModel.RuleItem> items = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            NotificationRule rule = rules.get(index);
            TargetDevice target = findTarget(devices, rule.targetDeviceId());
            items.add(
                    new PanelDisplayModel.RuleItem(
                            index + 1,
                            rule.ruleId(),
                            rule.sourceDeviceId(),
                            rule.sourceDeviceName(),
                            rule.targetDeviceId(),
                            rule.targetDeviceName(),
                            rule.status(),
                            ruleStatus(rule, target, accessState),
                            rule.summary(),
                            rule.dslJson(),
                            ruleNotice(rule, target, accessState)));
        }
        return items;
    }

    private static String targetNotice(TargetDevice device) {
        StringBuilder notice = new StringBuilder();
        if (!device.online()) {
            notice.append("当前离线：通知不会排队，也不会自动重试。");
        } else if (!device.reachable()) {
            notice.append("目标在线但当前不可达：通知不会排队，也不会自动重试。");
        }
        if (!device.targetAuthorized()) {
            appendSentence(notice, "目标尚未授权：在授权前规则不会正常转发。");
        }
        return notice.toString();
    }

    private static String ruleStatus(
            NotificationRule rule, TargetDevice target, NotificationAccessState accessState) {
        boolean active = "active".equalsIgnoreCase(rule.status());
        if ("paused".equalsIgnoreCase(rule.status())) {
            if (targetCannotReceive(target)) {
                return "已暂停（目标不可用，恢复前需处理）";
            }
            return "已暂停";
        }
        if (active && accessState != NotificationAccessState.GRANTED) {
            return "受阻（"
                    + accessLabel(accessState)
                    + "）";
        }
        if (active && target == null) {
            return "受阻（目标缺失）";
        }
        if (active && !target.supportsNotificationReceive()) {
            return "受阻（目标不可接收通知）";
        }
        if (active && target != null && !target.targetAuthorized()) {
            return "受阻（目标未授权）";
        }
        if (active) {
            return "已启用";
        }
        return rule.status();
    }

    private static String ruleNotice(
            NotificationRule rule, TargetDevice target, NotificationAccessState accessState) {
        StringBuilder notice = new StringBuilder();
        boolean active = "active".equalsIgnoreCase(rule.status());
        if (active && accessState != NotificationAccessState.GRANTED) {
            appendSentence(notice, "系统通知使用权" + accessLabel(accessState) + "，此规则受阻。");
        }
        if (target == null) {
            appendSentence(
                    notice,
                    isPaused(rule)
                            ? "规则已暂停；目标当前不可用，恢复前需处理。"
                            : "规则目标不在当前快照中，无法确认其状态。");
            return notice.toString();
        }
        if (!target.supportsNotificationReceive()) {
            appendSentence(
                    notice,
                    isPaused(rule)
                            ? "规则已暂停；目标当前不可用，恢复前需处理。"
                            : "规则目标不具备通知接收能力，此规则受阻。");
            return notice.toString();
        }
        if (!target.targetAuthorized()) {
            appendSentence(notice, "目标尚未授权，规则不会正常转发。");
        }
        if (!target.online()) {
            appendSentence(notice, "目标当前离线：通知不会排队，也不会自动重试。");
        } else if (!target.reachable()) {
            appendSentence(notice, "目标在线但当前不可达：通知不会排队，也不会自动重试。");
        }
        return notice.toString();
    }

    private static String targetSectionPlaceholder(
            ConnectionState state, boolean staleCache, boolean empty) {
        if (!empty) {
            return "";
        }
        if (staleCache) {
            return "缓存快照中没有支持接收能力的 PC、PAD 或 MAC 目标。";
        }
        if (!isSuccessfulRead(state)) {
            return "无法确定当前目标：尚未成功读取。";
        }
        return "当前没有支持接收能力的 PC、PAD 或 MAC 目标。";
    }

    private static String ruleSectionPlaceholder(
            ConnectionState state, boolean staleCache, boolean empty) {
        if (!empty) {
            return "";
        }
        if (staleCache) {
            return "缓存快照中没有演示规则。";
        }
        if (!isSuccessfulRead(state)) {
            return "无法确定当前规则：尚未成功读取。";
        }
        return "当前快照没有演示规则。";
    }

    private static boolean isSuccessfulRead(ConnectionState state) {
        return state == ConnectionState.CONNECTED
                || state == ConnectionState.COMPATIBILITY_UNVERIFIED;
    }

    private static boolean targetCannotReceive(TargetDevice target) {
        return target == null || !target.supportsNotificationReceive();
    }

    private static boolean isPaused(NotificationRule rule) {
        return "paused".equalsIgnoreCase(rule.status());
    }

    private static TargetDevice findTarget(List<TargetDevice> devices, String deviceId) {
        for (TargetDevice device : devices) {
            if (device.deviceId().equals(deviceId)) {
                return device;
            }
        }
        return null;
    }

    private static String notificationAccessText(NotificationAccessState state) {
        switch (state) {
            case GRANTED:
                return "系统通知使用权：已授予。它是本机全局能力，与目标授权相互独立。";
            case MISSING:
                return "系统通知使用权：缺失。通知流转受阻，请前往系统设置授予；当前页面只读。";
            case REVOKED:
                return "系统通知使用权：已撤销。通知流转受阻，请前往系统设置重新授予；当前页面只读。";
            default:
                throw new IllegalArgumentException("Unhandled notification access: " + state);
        }
    }

    private static String accessLabel(NotificationAccessState state) {
        return state == NotificationAccessState.MISSING ? "缺失" : "已撤销";
    }

    private static void appendSentence(StringBuilder text, String sentence) {
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(sentence);
    }
}
