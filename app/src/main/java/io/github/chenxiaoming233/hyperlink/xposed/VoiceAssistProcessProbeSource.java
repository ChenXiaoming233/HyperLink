package io.github.chenxiaoming233.hyperlink.xposed;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.rpc.RuntimeProbeSnapshot;
import io.github.chenxiaoming233.hyperlink.rpc.RuntimeProbeSource;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class VoiceAssistProcessProbeSource implements RuntimeProbeSource {
    private static final String TARGET = "voiceassist";
    private static volatile WeakReference<Object> listenerReference =
            new WeakReference<>(null);

    private final Context context;

    VoiceAssistProcessProbeSource(Context context) {
        this.context = context;
    }

    static void markListenerReady(Object listener) {
        listenerReference = new WeakReference<>(listener);
    }

    static void markListenerReleased(Object listener) {
        if (listenerReference.get() == listener) {
            listenerReference = new WeakReference<>(null);
        }
    }

    @Override
    public ServiceResult<RuntimeProbeSnapshot> read() {
        Object listener = listenerReference.get();
        if (listener == null) {
            return ServiceResult.failure("目标通知监听器尚未就绪。");
        }

        boolean ruleStoreReadable = false;
        int ruleCount = 0;
        List<Object> rules = Collections.emptyList();
        // Obfuscated listener fields are the tested VoiceAssist 8.2.70.8421 contract.
        Object store = fieldValue(listener, "a");
        if (store != null) {
            try {
                Object value = invokeNoArg(store, "getAllRules");
                if (value instanceof Collection) {
                    ruleStoreReadable = true;
                    rules = new ArrayList<>((Collection<?>) value);
                    ruleCount = rules.size();
                }
            } catch (Exception exception) {
                ProbeLog.failure(TARGET, "rule_store_read_failed", exception);
            }
        }

        boolean deviceInfoReadable = false;
        int targetCount = 0;
        // Field b is the notification-forward device service in the same process.
        Object deviceService = fieldValue(listener, "b");
        if (deviceService != null) {
            Set<String> targetDeviceIds = new LinkedHashSet<>();
            for (Object rule : rules) {
                try {
                    Object target = invokeNoArg(rule, "getTarget");
                    Object deviceId = invokeNoArg(target, "getDeviceId");
                    if (deviceId instanceof String && !((String) deviceId).isEmpty()) {
                        targetDeviceIds.add((String) deviceId);
                    }
                } catch (Exception exception) {
                    ProbeLog.failure(TARGET, "rule_target_read_failed", exception);
                }
            }
            for (String deviceId : targetDeviceIds) {
                try {
                    Object status = invokeWithArg(deviceService, "getDeviceStatus", deviceId);
                    if (status != null) {
                        deviceInfoReadable = true;
                        targetCount++;
                    }
                } catch (Exception exception) {
                    ProbeLog.failure(TARGET, "device_info_read_failed", exception);
                }
            }
        }

        return ServiceResult.success(
                new RuntimeProbeSnapshot(
                        true,
                        ruleStoreReadable,
                        ruleCount,
                        deviceInfoReadable,
                        targetCount,
                        notificationListenerAccess()));
    }

    private boolean notificationListenerAccess() {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            ComponentName listener =
                    new ComponentName(
                            "com.miui.voiceassist",
                            "com.aios.osbot.im.service.notification.NotificationForwardListener");
            Method method =
                    NotificationManager.class.getMethod(
                            "isNotificationListenerAccessGranted", ComponentName.class);
            return Boolean.TRUE.equals(method.invoke(manager, listener));
        } catch (ReflectiveOperationException exception) {
            ProbeLog.failure(TARGET, "listener_access_read_failed", exception);
            return false;
        }
    }

    private static Object fieldValue(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invokeWithArg(Object target, String methodName, Object argument)
            throws Exception {
        Method method = target.getClass().getMethod(methodName, argument.getClass());
        method.setAccessible(true);
        return method.invoke(target, argument);
    }
}
