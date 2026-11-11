package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.write.LastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleLkg;
import java.util.Objects;

public final class FakeLastKnownGoodStore implements LastKnownGoodStore {
    private RuleLkg snapshot;
    private int saveCount;
    private boolean failNextSave;
    private boolean failNextSaveWithException;

    @Override
    public synchronized boolean save(RuleLkg snapshot) {
        saveCount++;
        if (failNextSaveWithException) {
            failNextSaveWithException = false;
            throw new IllegalStateException("演示 LKG 保存异常。");
        }
        if (failNextSave) {
            failNextSave = false;
            return false;
        }
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        return true;
    }

    @Override
    public synchronized RuleLkg load() {
        return snapshot;
    }

    public synchronized void failNextSave() {
        failNextSave = true;
    }

    public synchronized void failNextSaveWithException() {
        failNextSaveWithException = true;
    }

    public synchronized int saveCount() {
        return saveCount;
    }
}
