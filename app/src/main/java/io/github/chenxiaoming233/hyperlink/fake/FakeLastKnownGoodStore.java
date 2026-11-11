package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.write.LastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleLkg;
import java.util.Objects;

public final class FakeLastKnownGoodStore implements LastKnownGoodStore {
    private RuleLkg snapshot;
    private int saveCount;
    private boolean failNextSave;

    @Override
    public synchronized boolean save(RuleLkg snapshot) {
        saveCount++;
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

    public synchronized int saveCount() {
        return saveCount;
    }
}
