package io.github.chenxiaoming233.hyperlink.rpc;

public final class CallerIdentity {
    private final int uid;
    private final int pid;

    public CallerIdentity(int uid, int pid) {
        if (uid <= 0) {
            throw new IllegalArgumentException("uid must be positive");
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        this.uid = uid;
        this.pid = pid;
    }

    public int uid() {
        return uid;
    }

    public int pid() {
        return pid;
    }
}
