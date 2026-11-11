package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import java.util.Objects;

public final class PanelStateMapper {
    private PanelStateMapper() {}

    public static PanelDisplayModel map(
            ConnectionState state, boolean compatibilityUnverified) {
        Objects.requireNonNull(state, "state");
        boolean showCompatibility =
                compatibilityUnverified || state == ConnectionState.COMPATIBILITY_UNVERIFIED;
        boolean managementEnabled =
                state == ConnectionState.CONNECTED
                        || state == ConnectionState.COMPATIBILITY_UNVERIFIED;

        switch (state) {
            case CONNECTED:
                return model(
                        "运行环境已连接",
                        "已从演示服务读取状态；真实 VoiceAssist 适配器尚未接入。",
                        managementEnabled,
                        false,
                        showCompatibility);
            case NOT_ACTIVATED:
                return model(
                        "模块尚未激活",
                        "请在 LSPosed 中启用模块并重启目标应用；当前仅显示演示状态。",
                        managementEnabled,
                        true,
                        showCompatibility);
            case TARGET_PROCESS_UNREACHABLE:
                return model(
                        "目标进程不可达",
                        "演示服务无法访问 VoiceAssist 进程，未执行任何真实读取或写入。",
                        managementEnabled,
                        true,
                        showCompatibility);
            case COMPATIBILITY_UNVERIFIED:
                return model(
                        "兼容性未经完整验收",
                        "只能执行受控操作；当前没有连接真实 VoiceAssist，也未完成设备验收。",
                        managementEnabled,
                        false,
                        showCompatibility);
            case CAPABILITY_INSUFFICIENT:
                return model(
                        "能力不足",
                        "演示服务报告关键能力不足，规则管理保持只读并禁止写入。",
                        managementEnabled,
                        true,
                        showCompatibility);
            default:
                throw new IllegalArgumentException("Unhandled connection state: " + state);
        }
    }

    public static PanelDisplayModel mapFailure(String safeUserMessage) {
        String message =
                safeUserMessage == null || safeUserMessage.trim().isEmpty()
                        ? "无法读取通知流转状态，请稍后重试。"
                        : safeUserMessage;
        return model("读取失败", message, false, true, false);
    }

    private static PanelDisplayModel model(
            String title,
            String message,
            boolean managementEnabled,
            boolean retryEnabled,
            boolean compatibilityBadgeVisible) {
        return new PanelDisplayModel(
                title, message, managementEnabled, retryEnabled, compatibilityBadgeVisible);
    }
}
