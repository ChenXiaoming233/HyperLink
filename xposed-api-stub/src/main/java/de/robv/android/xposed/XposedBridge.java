package de.robv.android.xposed;

public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String message) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static void log(Throwable throwable) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
