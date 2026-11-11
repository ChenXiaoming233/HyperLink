package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RuleBranch {
    private final List<RuleCondition> conditions;

    private RuleBranch(List<RuleCondition> conditions) {
        this.conditions = immutableConditions(conditions);
        if (this.conditions.isEmpty()) {
            throw new IllegalArgumentException("A rule branch requires at least one condition");
        }
    }

    public static RuleBranch of(RuleCondition... conditions) {
        Objects.requireNonNull(conditions, "conditions");
        return new RuleBranch(Arrays.asList(conditions));
    }

    public static RuleBranch of(List<RuleCondition> conditions) {
        return new RuleBranch(conditions);
    }

    public List<RuleCondition> conditions() {
        return conditions;
    }

    public RuleBranch addCondition(RuleCondition condition) {
        List<RuleCondition> next = new ArrayList<>(conditions);
        next.add(Objects.requireNonNull(condition, "condition"));
        return new RuleBranch(next);
    }

    public RuleBranch removeCondition(int index) {
        List<RuleCondition> next = new ArrayList<>(conditions);
        next.remove(checkedIndex(index, next.size()));
        return new RuleBranch(next);
    }

    public RuleBranch moveCondition(int fromIndex, int toIndex) {
        List<RuleCondition> next = new ArrayList<>(conditions);
        move(next, fromIndex, toIndex);
        return new RuleBranch(next);
    }

    public RuleBranch replaceCondition(int index, RuleCondition condition) {
        List<RuleCondition> next = new ArrayList<>(conditions);
        next.set(
                checkedIndex(index, next.size()),
                Objects.requireNonNull(condition, "condition"));
        return new RuleBranch(next);
    }

    private static List<RuleCondition> immutableConditions(List<RuleCondition> values) {
        Objects.requireNonNull(values, "conditions");
        List<RuleCondition> copy = new ArrayList<>(values.size());
        for (RuleCondition value : values) {
            copy.add(Objects.requireNonNull(value, "condition"));
        }
        return Collections.unmodifiableList(copy);
    }

    static int checkedIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " for size " + size);
        }
        return index;
    }

    static <T> void move(List<T> values, int fromIndex, int toIndex) {
        checkedIndex(fromIndex, values.size());
        checkedIndex(toIndex, values.size());
        T value = values.remove(fromIndex);
        values.add(toIndex, value);
    }
}
