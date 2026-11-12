package io.github.chenxiaoming233.hyperlink;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import io.github.chenxiaoming233.hyperlink.rpc.RuntimeProbeContract;

public final class RuntimeProbeInstrumentation extends Instrumentation {
    private static final String AUTHORITY =
            "com.miui.voiceassist.contentprovider.LinkContentProvider";
    private int protocolVersion = 1;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        protocolVersion =
                arguments.getInt(RuntimeProbeContract.EXTRA_PROTOCOL_VERSION, 1);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Bundle extras = new Bundle();
            extras.putInt(RuntimeProbeContract.EXTRA_PROTOCOL_VERSION, protocolVersion);
            Bundle response =
                    getTargetContext()
                            .getContentResolver()
                            .call(
                                    Uri.parse("content://" + AUTHORITY),
                                    RuntimeProbeContract.METHOD,
                                    null,
                                    extras);
            result.putString("response", summarize(response));
            result.putBoolean(
                    "success",
                    response != null
                            && response.getBoolean(RuntimeProbeContract.KEY_SUCCESS, false));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable throwable) {
            result.putString(
                    "error",
                    throwable.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(throwable.getMessage()));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static String summarize(Bundle response) {
        if (response == null) {
            return "response=null";
        }
        return "success="
                + response.getBoolean(RuntimeProbeContract.KEY_SUCCESS, false)
                + " errorCode="
                + response.getString(RuntimeProbeContract.KEY_ERROR_CODE)
                + " storeReady="
                + response.getBoolean(RuntimeProbeContract.KEY_STORE_READY, false)
                + " ruleStoreReadable="
                + response.getBoolean(RuntimeProbeContract.KEY_RULE_STORE_READABLE, false)
                + " ruleCount="
                + response.getInt(RuntimeProbeContract.KEY_RULE_COUNT, -1)
                + " deviceInfoReadable="
                + response.getBoolean(RuntimeProbeContract.KEY_DEVICE_INFO_READABLE, false)
                + " targetCount="
                + response.getInt(RuntimeProbeContract.KEY_TARGET_COUNT, -1)
                + " notificationListenerAccess="
                + response.getBoolean(
                        RuntimeProbeContract.KEY_NOTIFICATION_LISTENER_ACCESS, false);
    }
}
