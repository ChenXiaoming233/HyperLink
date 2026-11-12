package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("compile-time stub");
        }
    }

    public static final class Unhook {
        public void unhook() {
            throw new UnsupportedOperationException("compile-time stub");
        }
    }
}
