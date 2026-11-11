package io.github.chenxiaoming233.hyperlink.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperLinkModule implements IXposedHookLoadPackage {
    private static final String VOICE_ASSIST_PACKAGE = "com.miui.voiceassist";
    private static final String MILINK_PACKAGE = "com.milink.service";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!VOICE_ASSIST_PACKAGE.equals(loadPackageParam.packageName)
                && !MILINK_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }
        XposedBridge.log(
                "HyperLink: module skeleton loaded for "
                        + loadPackageParam.packageName
                        + "; no hook installed");
    }
}
