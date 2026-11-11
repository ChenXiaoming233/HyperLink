package io.github.chenxiaoming233.hyperlink.ui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class RuleEditorRefreshCoordinator<T> {
    enum Area {
        APP,
        STRUCTURE,
        EXISTING_RULES
    }

    private final Map<Area, EditableViewTracker<T>> trackers =
            new EnumMap<>(Area.class);
    private final Map<Area, Long> areaGenerations = new EnumMap<>(Area.class);
    private final EnumSet<Area> begunAreas = EnumSet.noneOf(Area.class);
    private long generation;

    RuleEditorRefreshCoordinator() {
        for (Area area : Area.values()) {
            trackers.put(area, new EditableViewTracker<>());
            areaGenerations.put(area, 0L);
        }
    }

    void track(Area area, T view) {
        tracker(area).track(view);
    }

    void forEach(Area area, Consumer<? super T> action) {
        tracker(area).forEach(action);
    }

    long beginRebuild(Area area) {
        tracker(area).clear();
        generation++;
        areaGenerations.put(area, areaGenerations.get(area) + 1L);
        begunAreas.add(area);
        return generation;
    }

    int trackedCount(Area area) {
        return tracker(area).size();
    }

    long generation() {
        return generation;
    }

    long generation(Area area) {
        return areaGenerations.get(Objects.requireNonNull(area, "area"));
    }

    Set<Area> begunAreas() {
        return Collections.unmodifiableSet(EnumSet.copyOf(begunAreas));
    }

    private EditableViewTracker<T> tracker(Area area) {
        return Objects.requireNonNull(
                trackers.get(Objects.requireNonNull(area, "area")),
                "area tracker");
    }
}
