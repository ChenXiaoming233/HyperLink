package io.github.chenxiaoming233.hyperlink.domain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuleCondition {
    public enum Field {
        APP,
        ISLAND,
        VERIFY_CODE,
        SENDER,
        TEXT
    }

    public enum Operator {
        IN,
        NOT_IN,
        IS,
        REGEX,
        NOT_REGEX
    }

    private final Field field;
    private final Operator operator;
    private final List<String> appPackages;
    private final boolean booleanValue;
    private final String pattern;

    private RuleCondition(
            Field field,
            Operator operator,
            List<String> appPackages,
            boolean booleanValue,
            String pattern) {
        this.field = Objects.requireNonNull(field, "field");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.appPackages =
                field == Field.APP
                        ? immutablePackages(appPackages)
                        : Collections.emptyList();
        this.booleanValue = booleanValue;
        this.pattern = pattern == null ? "" : pattern;
        validate();
    }

    public static RuleCondition appIn(List<String> packageNames) {
        return new RuleCondition(Field.APP, Operator.IN, packageNames, false, "");
    }

    public static RuleCondition appNotIn(List<String> packageNames) {
        return new RuleCondition(Field.APP, Operator.NOT_IN, packageNames, false, "");
    }

    public static RuleCondition isIsland(boolean value) {
        return new RuleCondition(
                Field.ISLAND, Operator.IS, Collections.emptyList(), value, "");
    }

    public static RuleCondition isVerifyCode(boolean value) {
        return new RuleCondition(
                Field.VERIFY_CODE, Operator.IS, Collections.emptyList(), value, "");
    }

    public static RuleCondition senderRegex(String regex) {
        return regexCondition(Field.SENDER, Operator.REGEX, regex);
    }

    public static RuleCondition senderNotRegex(String regex) {
        return regexCondition(Field.SENDER, Operator.NOT_REGEX, regex);
    }

    public static RuleCondition textRegex(String regex) {
        return regexCondition(Field.TEXT, Operator.REGEX, regex);
    }

    public static RuleCondition textNotRegex(String regex) {
        return regexCondition(Field.TEXT, Operator.NOT_REGEX, regex);
    }

    public static String regexIssue(String regex) {
        if (regex == null) {
            return "正则表达式不能为空。";
        }
        try {
            Pattern.compile(regex);
            return "";
        } catch (RuntimeException exception) {
            return "正则语法无效，请检查括号、转义、分组和量词。";
        }
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

    public String pattern() {
        return pattern;
    }

    public RuleCondition withAppPackages(List<String> packageNames) {
        if (field != Field.APP) {
            throw new IllegalStateException("Only app conditions have package sets");
        }
        return new RuleCondition(field, operator, packageNames, false, "");
    }

    public RuleCondition withOperator(Operator nextOperator) {
        Objects.requireNonNull(nextOperator, "nextOperator");
        if (field == Field.APP) {
            return new RuleCondition(field, nextOperator, appPackages, false, "");
        }
        if (field == Field.SENDER || field == Field.TEXT) {
            return new RuleCondition(
                    field, nextOperator, Collections.emptyList(), false, pattern);
        }
        if (nextOperator != Operator.IS) {
            throw new IllegalArgumentException("Boolean conditions require is");
        }
        return new RuleCondition(
                field, Operator.IS, Collections.emptyList(), booleanValue, "");
    }

    public RuleCondition withBooleanValue(boolean nextValue) {
        if (field != Field.ISLAND && field != Field.VERIFY_CODE) {
            throw new IllegalStateException("Only boolean conditions have boolean values");
        }
        return new RuleCondition(
                field, Operator.IS, Collections.emptyList(), nextValue, "");
    }

    public RuleCondition withPattern(String nextPattern) {
        if (field != Field.SENDER && field != Field.TEXT) {
            throw new IllegalStateException("Only sender and text conditions use regex");
        }
        return new RuleCondition(
                field, operator, Collections.emptyList(), false, nextPattern);
    }

    private static RuleCondition regexCondition(
            Field field, Operator operator, String regex) {
        String issue = regexIssue(regex);
        if (!issue.isEmpty()) {
            throw new IllegalArgumentException(issue);
        }
        return new RuleCondition(
                field,
                operator,
                Collections.emptyList(),
                false,
                Objects.requireNonNull(regex, "regex"));
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
        if (field == Field.SENDER || field == Field.TEXT) {
            if (operator != Operator.REGEX && operator != Operator.NOT_REGEX) {
                throw new IllegalArgumentException(
                        "Sender and text conditions require regex or not_regex");
            }
            String issue = regexIssue(pattern);
            if (!issue.isEmpty()) {
                throw new IllegalArgumentException(issue);
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
