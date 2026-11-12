package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;
import java.util.Objects;

final class RuleEditorLoadState {
    private final boolean writable;
    private final String message;

    private RuleEditorLoadState(boolean writable, String message) {
        this.writable = writable;
        this.message = Objects.requireNonNull(message, "message");
    }

    static RuleEditorLoadState from(RuleStoreGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        try {
            ServiceResult<?> result = gateway.read();
            if (!result.isSuccess()) {
                return blocked("无法读取共享 fake store，生命周期编辑器已隐藏写入操作。");
            }
            String readOnlyFault = gateway.readOnlyFaultMessage();
            if (!readOnlyFault.isEmpty()) {
                return blocked("共享 fake store 处于只读故障态：" + readOnlyFault);
            }
            return new RuleEditorLoadState(true, "");
        } catch (RuntimeException exception) {
            return blocked("无法安全读取共享 fake store，生命周期编辑器已隐藏写入操作。");
        }
    }

    boolean writable() {
        return writable;
    }

    static RuleEditorLoadState writeFailure() {
        return blocked("规则写入结果无法安全确认，生命周期编辑器已隐藏写入操作。");
    }

    String message() {
        return message;
    }

    private static RuleEditorLoadState blocked(String message) {
        return new RuleEditorLoadState(false, message);
    }
}
