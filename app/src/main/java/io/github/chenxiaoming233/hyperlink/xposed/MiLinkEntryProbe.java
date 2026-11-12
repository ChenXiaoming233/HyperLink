package io.github.chenxiaoming233.hyperlink.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class MiLinkEntryProbe {
    private static final String TARGET = "milink";
    private static final String FRAGMENT_CLASS =
            "com.miui.circulate.world.ui.connectivitysettings.ConnectivityFragment";
    private static final String CATEGORY_KEY = "category_pref_key_cross_device";
    private static final String TEMPLATE_KEY = "text_pref_key_share_notification";
    private static final String MODULE_PACKAGE = "io.github.chenxiaoming233.hyperlink";
    private static final String ENTRY_KEY = "hyperlink_notification_flow";

    private MiLinkEntryProbe() {}

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                    FRAGMENT_CLASS,
                    loadPackageParam.classLoader,
                    "onCreatePreferences",
                    Bundle.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installEntry(param.thisObject);
                        }
                    });
            ProbeLog.event(TARGET, "hook_installed", "point=ConnectivityFragment.onCreatePreferences");
        } catch (Throwable throwable) {
            ProbeLog.failure(TARGET, "hook_install_failed", throwable);
        }
    }

    private static void installEntry(Object fragment) {
        try {
            Object existing = XposedHelpers.callMethod(fragment, "findPreference", ENTRY_KEY);
            if (existing != null) {
                ProbeLog.event(TARGET, "entry_present", "preference=" + ENTRY_KEY);
                return;
            }

            Object category = XposedHelpers.callMethod(fragment, "findPreference", CATEGORY_KEY);
            Object template = XposedHelpers.callMethod(fragment, "findPreference", TEMPLATE_KEY);
            Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            if (category == null || template == null || context == null) {
                ProbeLog.event(
                        TARGET,
                        "entry_missing_anchor",
                        "category="
                                + (category != null)
                                + " template="
                                + (template != null)
                                + " context="
                                + (context != null));
                return;
            }

            Object preference = XposedHelpers.newInstance(template.getClass(), context);
            invokeIfPresent(preference, "setKey", ENTRY_KEY);
            invokeIfPresent(preference, "setTitle", "通知流转");
            invokeIfPresent(preference, "setSummary", "打开 HyperLink 通知流转设置");
            invokeIfPresent(preference, "setPersistent", false);
            installClickListener(preference, context);
            invokeAddPreference(category, preference);

            ProbeLog.event(
                    TARGET,
                    "entry_injected",
                    "preference="
                            + ENTRY_KEY
                            + " category="
                            + CATEGORY_KEY
                            + " page=ConnectivityFragment");
        } catch (Throwable throwable) {
            ProbeLog.failure(TARGET, "entry_inject_failed", throwable);
        }
    }

    private static void installClickListener(Object preference, Context context) {
        for (Method method : preference.getClass().getMethods()) {
            if (!"setOnPreferenceClickListener".equals(method.getName())
                    || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> listenerType = method.getParameterTypes()[0];
            if (!listenerType.isInterface()) {
                continue;
            }
            Object listener =
                    Proxy.newProxyInstance(
                            listenerType.getClassLoader(),
                            new Class<?>[] {listenerType},
                            new ClickHandler(context));
            invoke(preference, method, listener);
            return;
        }
        throw new IllegalStateException("preference click listener is unavailable");
    }

    private static void invokeAddPreference(Object category, Object preference) {
        for (String methodName : new String[] {"addPreference", "k"}) {
            for (Method method : category.getClass().getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (methodName.equals(method.getName())
                        && parameterTypes.length == 1
                        && parameterTypes[0].isAssignableFrom(preference.getClass())) {
                    invoke(category, method, preference);
                    return;
                }
            }
        }
        throw new IllegalStateException(
                "preference group add method is unavailable");
    }

    private static void invokeIfPresent(Object target, String methodName, Object argument) {
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (methodName.equals(method.getName())
                    && parameterTypes.length == 1
                    && accepts(parameterTypes[0], argument)) {
                invoke(target, method, argument);
                return;
            }
        }
    }

    private static boolean accepts(Class<?> parameterType, Object argument) {
        if (parameterType.isInstance(argument)) {
            return true;
        }
        if (!parameterType.isPrimitive()) {
            return false;
        }
        if (parameterType == boolean.class) {
            return argument instanceof Boolean;
        }
        if (parameterType == int.class) {
            return argument instanceof Integer;
        }
        if (parameterType == long.class) {
            return argument instanceof Long;
        }
        return false;
    }

    private static void invoke(Object target, Method method, Object argument) {
        try {
            method.invoke(target, argument);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("preference setup failed", exception);
        }
    }

    private static final class ClickHandler implements InvocationHandler {
        private final Context context;

        private ClickHandler(Context context) {
            this.context = context;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args != null
                    && args.length == 1
                    && method.getReturnType() == boolean.class) {
                Intent intent =
                        context.getPackageManager().getLaunchIntentForPackage(MODULE_PACKAGE);
                if (intent == null) {
                    ProbeLog.event(TARGET, "entry_launch_failed", "reason=launcher_missing");
                    return false;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                ProbeLog.event(TARGET, "entry_launched", "package=" + MODULE_PACKAGE);
                return true;
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(method.getName())) {
                return "HyperLinkPreferenceClick";
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
