package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public final class RuleEditorRefreshCoordinatorTest {
    @Test
    public void everyAreaClearsItsTrackedViewsAndAdvancesGeneration() {
        RuleEditorRefreshCoordinator<String> coordinator =
                new RuleEditorRefreshCoordinator<>();
        for (RuleEditorRefreshCoordinator.Area area
                : RuleEditorRefreshCoordinator.Area.values()) {
            coordinator.track(area, "old-" + area.name());
            coordinator.track(area, "older-" + area.name());
        }

        long expectedGeneration = 0L;
        for (RuleEditorRefreshCoordinator.Area area
                : RuleEditorRefreshCoordinator.Area.values()) {
            long nextGeneration = coordinator.beginRebuild(area);

            expectedGeneration++;
            assertEquals(expectedGeneration, nextGeneration);
            assertEquals(expectedGeneration, coordinator.generation());
            assertEquals(1L, coordinator.generation(area));
            assertEquals(0, coordinator.trackedCount(area));
        }

        assertEquals(
                Set.copyOf(EnumSet.allOf(RuleEditorRefreshCoordinator.Area.class)),
                coordinator.begunAreas());
        assertTrue(coordinator.generation() == RuleEditorRefreshCoordinator.Area.values().length);
    }
}
