package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class EditableViewTrackerTest {
    @Test
    public void clearReleasesRebuiltViewsSoLongSessionsDoNotRetainDetachedViews() {
        EditableViewTracker<String> tracker = new EditableViewTracker<>();
        tracker.track("old-a");
        tracker.track("old-b");

        assertEquals(2, tracker.size());

        tracker.clear();

        assertEquals(0, tracker.size());
        AtomicBoolean visited = new AtomicBoolean();
        tracker.forEach(value -> visited.set(true));
        assertFalse(visited.get());

        tracker.track("new");
        assertEquals(1, tracker.size());
        AtomicBoolean newVisited = new AtomicBoolean();
        tracker.forEach(value -> newVisited.set("new".equals(value)));
        assertTrue(newVisited.get());
    }
}
