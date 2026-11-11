package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RuleAndGroup {
    private final List<RuleBranch> branches;

    private RuleAndGroup(List<RuleBranch> branches) {
        this.branches = immutableBranches(branches);
        if (this.branches.isEmpty()) {
            throw new IllegalArgumentException("An andGroup requires at least one branch");
        }
    }

    public static RuleAndGroup of(RuleBranch... branches) {
        Objects.requireNonNull(branches, "branches");
        return new RuleAndGroup(Arrays.asList(branches));
    }

    public static RuleAndGroup of(List<RuleBranch> branches) {
        return new RuleAndGroup(branches);
    }

    public List<RuleBranch> branches() {
        return branches;
    }

    public RuleAndGroup addBranch(RuleBranch branch) {
        List<RuleBranch> next = new ArrayList<>(branches);
        next.add(Objects.requireNonNull(branch, "branch"));
        return new RuleAndGroup(next);
    }

    public RuleAndGroup removeBranch(int index) {
        List<RuleBranch> next = new ArrayList<>(branches);
        next.remove(RuleBranch.checkedIndex(index, next.size()));
        return new RuleAndGroup(next);
    }

    public RuleAndGroup moveBranch(int fromIndex, int toIndex) {
        List<RuleBranch> next = new ArrayList<>(branches);
        RuleBranch.move(next, fromIndex, toIndex);
        return new RuleAndGroup(next);
    }

    public RuleAndGroup replaceBranch(int index, RuleBranch branch) {
        List<RuleBranch> next = new ArrayList<>(branches);
        next.set(
                RuleBranch.checkedIndex(index, next.size()),
                Objects.requireNonNull(branch, "branch"));
        return new RuleAndGroup(next);
    }

    private static List<RuleBranch> immutableBranches(List<RuleBranch> values) {
        Objects.requireNonNull(values, "branches");
        List<RuleBranch> copy = new ArrayList<>(values.size());
        for (RuleBranch value : values) {
            copy.add(Objects.requireNonNull(value, "branch"));
        }
        return Collections.unmodifiableList(copy);
    }
}
