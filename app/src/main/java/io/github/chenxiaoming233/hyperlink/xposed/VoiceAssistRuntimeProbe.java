package io.github.chenxiaoming233.hyperlink.xposed;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.chenxiaoming233.hyperlink.BuildConfig;
import io.github.chenxiaoming233.hyperlink.rpc.CallerAuthenticator;
import io.github.chenxiaoming233.hyperlink.rpc.CallerIdentity;
import io.github.chenxiaoming233.hyperlink.rpc.PinnedCallerAuthenticator;
import io.github.chenxiaoming233.hyperlink.rpc.RpcErrorCode;
import io.github.chenxiaoming233.hyperlink.rpc.RpcOperation;
import io.github.chenxiaoming233.hyperlink.rpc.RpcProtocol;
import io.github.chenxiaoming233.hyperlink.rpc.RpcRequest;
import io.github.chenxiaoming233.hyperlink.rpc.RpcResponse;
import io.github.chenxiaoming233.hyperlink.rpc.RuntimeProbeContract;
import io.github.chenxiaoming233.hyperlink.rpc.RuntimeProbeHandler;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

final class VoiceAssistRuntimeProbe {
    private static final String TARGET = "voiceassist";
    private static final String PROVIDER_CLASS =
            "com.xiaomi.voiceassistant.contentprovider.LinkContentProvider";
    private static final String LISTENER_CLASS =
            "com.aios.osbot.im.service.notification.NotificationForwardListener";
    private static final String PANEL_PACKAGE = "io.github.chenxiaoming233.hyperlink";

    private VoiceAssistRuntimeProbe() {}

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        installProviderHook(loadPackageParam.classLoader);
        installListenerHooks(loadPackageParam.classLoader);
    }

    private static void installProviderHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    PROVIDER_CLASS,
                    classLoader,
                    "call",
                    String.class,
                    String.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleProviderCall(param);
                        }
                    });
            ProbeLog.event(TARGET, "provider_hook_installed", "provider=LinkContentProvider");
        } catch (Throwable throwable) {
            ProbeLog.failure(TARGET, "provider_hook_install_failed", throwable);
        }
    }

    private static void installListenerHooks(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    LISTENER_CLASS,
                    classLoader,
                    "onListenerConnected",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            VoiceAssistProcessProbeSource.markListenerReady(param.thisObject);
                            ProbeLog.event(TARGET, "listener_ready", "source=callback");
                        }
                    });
            XC_MethodHook releaseHook =
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            VoiceAssistProcessProbeSource.markListenerReleased(param.thisObject);
                            ProbeLog.event(TARGET, "listener_released", "source=callback");
                        }
                    };
            XposedHelpers.findAndHookMethod(
                    LISTENER_CLASS, classLoader, "onListenerDisconnected", releaseHook);
            XposedHelpers.findAndHookMethod(
                    LISTENER_CLASS, classLoader, "onDestroy", releaseHook);
            ProbeLog.event(TARGET, "listener_hooks_installed", "point=onListenerConnected");
        } catch (Throwable throwable) {
            ProbeLog.failure(TARGET, "listener_hook_install_failed", throwable);
        }
    }

    private static void handleProviderCall(XC_MethodHook.MethodHookParam param) {
        String method = (String) param.args[0];
        if (!RuntimeProbeContract.METHOD.equals(method)) {
            if (method != null && method.startsWith("hyperlink.")) {
                param.setResult(errorBundle(RpcErrorCode.INVALID_REQUEST));
            }
            return;
        }

        try {
            Context context =
                    (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
            Bundle extras = (Bundle) param.args[2];
            int protocolVersion =
                    extras == null
                            ? -1
                            : extras.getInt(
                                    RuntimeProbeContract.EXTRA_PROTOCOL_VERSION,
                                    RpcProtocol.VERSION + 1);
            RpcResponse response =
                    newHandler(context)
                            .handle(
                                    new RpcRequest(
                                            protocolVersion,
                                            "runtime-probe",
                                            RpcOperation.PROBE_TARGET,
                                            Collections.emptyMap()),
                                    new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid()));
            param.setResult(toBundle(response));
            ProbeLog.event(
                    TARGET,
                    "provider_call",
                    "result="
                            + (response.success() ? "ok" : response.errorCode().name())
                            + " uid="
                            + Binder.getCallingUid()
                            + " protocol="
                            + protocolVersion);
        } catch (Throwable throwable) {
            param.setResult(errorBundle(RpcErrorCode.SERVICE_UNAVAILABLE));
            ProbeLog.failure(TARGET, "provider_call_failed", throwable);
        }
    }

    private static RuntimeProbeHandler newHandler(Context context) {
        Set<String> trustedSigners =
                BuildConfig.PROBE_CALLER_SIGNER_SHA256.isEmpty()
                        ? Collections.emptySet()
                        : Collections.singleton(BuildConfig.PROBE_CALLER_SIGNER_SHA256);
        CallerAuthenticator authenticator =
                trustedSigners.isEmpty()
                        ? caller -> false
                        : new PinnedCallerAuthenticator(PANEL_PACKAGE, trustedSigners);
        return new RuntimeProbeHandler(
                new RpcProtocol(RpcProtocol.VERSION),
                new AndroidCallerEvidenceResolver(context),
                authenticator,
                new VoiceAssistProcessProbeSource(context));
    }

    private static Bundle toBundle(RpcResponse response) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(RuntimeProbeContract.KEY_SUCCESS, response.success());
        bundle.putString(RuntimeProbeContract.KEY_ERROR_CODE, response.errorCode().name());
        bundle.putString(RuntimeProbeContract.KEY_USER_MESSAGE, response.userMessage());
        for (Map.Entry<String, Object> entry : response.data().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                bundle.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof Integer) {
                bundle.putInt(entry.getKey(), (Integer) value);
            }
        }
        return bundle;
    }

    private static Bundle errorBundle(RpcErrorCode errorCode) {
        return toBundle(RpcResponse.failure("runtime-probe", errorCode));
    }
}
