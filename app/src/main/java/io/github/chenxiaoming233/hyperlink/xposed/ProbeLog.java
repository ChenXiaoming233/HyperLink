package io.github.chenxiaoming233.hyperlink.xposed;

import de.robv.android.xposed.XposedBridge;

final class ProbeLog {
    private ProbeLog() {}

    static void event(String target, String event, String details) {
        XposedBridge.log(
                "HyperLinkProbe target="
                        + target
                        + " event="
                        + event
                        + (details.isEmpty() ? "" : " " + details));
    }

    static void failure(String target, String event, Throwable throwable) {
        String type = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        event(target, event, "result=failed errorType=" + type);
    }
}
