package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RuleCondition {
    public enum Field {
        APP,
        ISLAND,
        VERIFY_CODE
    }

    public enum Operator {
        IN,
        NOT_IN,
        IS
    }

    private final Field field;
    private final Operator operator;
    private final List<String> appPackages;
    private final boolean booleanValue;

    private RuleCondition(
            Field field,
            Operator operator,
            List<String> appPackages,
            boolean booleanValue) {
        this.field = Objects.requireNonNull(field, "field");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.appPackages =
                field == Field.APP
                        ? immutablePackages(appPackages)
                        : Collections.emptyList();
        this.booleanValue = booleanValue;
        validate();
    }

    public static RuleCondition appIn(List<String> packageNames) {
        return new RuleCondition(Field.APP, Operator.IN, packageNames, false);
    }

    public static RuleCondition appNotIn(List<String> packageNames) {
        return new RuleCondition(Field.APP, Operator.NOT_IN, packageNames, false);
    }

    public static RuleCondition isIsland(boolean value) {
        return new RuleCondition(Field.ISLAND, Operator.IS, Collections.emptyList(), value);
    }

    public static RuleCondition isVerifyCode(boolean value) {
        return new RuleCondition(
                Field.VERIFY_CODE, Operator.IS, Collections.emptyList(), value);
    }

    public Field field() {
        return field;
    }

    public Operator operator() {
        return operator;
    }

    public List<String> appPackages() {
        return appPackages;
    }

    public boolean booleanValue() {
        return booleanValue;
    }

    public RuleCondition withAppPackages(List<String> packageNames) {
        if (field != Field.APP) {
            throw new IllegalStateException("Only app conditions have package sets");
        }
        return new RuleCondition(field, operator, packageNames, false);
    }

    public RuleCondition withOperator(Operator nextOperator) {
        if (field != Field.APP) {
            throw new IllegalStateException("Only app conditions have collection operators");
        }
        return new RuleCondition(field, nextOperator, appPackages, false);
    }

    public RuleCondition withBooleanValue(boolean nextValue) {
        if (field == Field.APP) {
            throw new IllegalStateException("App conditions do not have boolean values");
        }
        return new RuleCondition(field, Operator.IS, Collections.emptyList(), nextValue);
    }

    private void validate() {
        if (field == Field.APP) {
            if (operator != Operator.IN && operator != Operator.NOT_IN) {
                throw new IllegalArgumentException("App conditions require in or not_in");
            }
            if (appPackages.isEmpty()) {
                throw new IllegalArgumentException("App conditions require at least one package");
            }
            return;
        }
        if (operator != Operator.IS) {
            throw new IllegalArgumentException("Boolean conditions require is");
        }
    }

    private static List<String> immutablePackages(List<String> packageNames) {
        Objects.requireNonNull(packageNames, "packageNames");
        Set<String> unique = new LinkedHashSet<>();
        for (String packageName : packageNames) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be blank");
            }
            unique.add(packageName);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
