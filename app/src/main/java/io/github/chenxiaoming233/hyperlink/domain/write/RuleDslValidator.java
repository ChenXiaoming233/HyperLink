package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuleDslValidator {
    private static final int MAX_NESTING_DEPTH = 64;
    private static final Set<String> ROOT_FIELDS =
            setOf("andGroups", "mode", "ruleText");
    private static final Set<String> GROUP_FIELDS = setOf("branches");
    private static final Set<String> CONDITION_FIELDS =
            setOf("field", "op", "value");
    private static final Set<String> KNOWN_FIELDS =
            setOf("app", "isIsland", "isVerifyCode", "sender", "text");
    private static final Set<String> KNOWN_OPERATORS =
            setOf("in", "not_in", "is", "regex", "not_regex");

    private RuleDslValidator() {}

    static boolean isValid(String dslJson) {
        return issue(dslJson).isEmpty();
    }

    static String issue(String dslJson) {
        try {
            Object parsed = new Parser(dslJson).parse();
            if (!(parsed instanceof Map)) {
                return "DSL 根结构必须是对象。";
            }
            return rootIssue(castMap(parsed));
        } catch (IllegalArgumentException exception) {
            return "DSL 不是可解析的合法 JSON 结构。";
        }
    }

    static Object parse(String dslJson) {
        return new Parser(dslJson).parse();
    }

    private static String rootIssue(Map<String, Object> root) {
        if (!ROOT_FIELDS.containsAll(root.keySet())) {
            Set<String> unknown = new LinkedHashSet<>(root.keySet());
            unknown.removeAll(ROOT_FIELDS);
            return "DSL 根对象包含未知字段：" + String.join("、", unknown) + "。";
        }
        Object ruleText = root.get("ruleText");
        if (!(ruleText instanceof String) || ((String) ruleText).trim().isEmpty()) {
            return "DSL 缺失非空 ruleText。";
        }

        boolean hasMode = root.containsKey("mode");
        boolean hasGroups = root.containsKey("andGroups");
        if (hasMode) {
            if (!"allEligibleNotifications".equals(root.get("mode")) || hasGroups) {
                return "DSL mode 与筛选结构组合不可表达。";
            }
        }
        // Existing rules may express "all eligible notifications" with ruleText only.
        return hasGroups ? groupsIssue(root.get("andGroups")) : "";
    }

    private static String groupsIssue(Object groupsValue) {
        if (!(groupsValue instanceof List) || ((List<?>) groupsValue).isEmpty()) {
            return "DSL andGroups 必须是非空数组。";
        }
        for (Object groupValue : (List<?>) groupsValue) {
            if (!(groupValue instanceof Map)) {
                return "DSL andGroups 的成员必须是对象。";
            }
            Map<String, Object> group = castMap(groupValue);
            if (!group.keySet().equals(GROUP_FIELDS)) {
                Set<String> unknown = new LinkedHashSet<>(group.keySet());
                unknown.removeAll(GROUP_FIELDS);
                if (!unknown.isEmpty()) {
                    return "AND 组包含未知字段：" + String.join("、", unknown) + "。";
                }
                return "AND 组缺少 branches。";
            }
            Object branchesValue = group.get("branches");
            if (!(branchesValue instanceof List) || ((List<?>) branchesValue).isEmpty()) {
                return "AND 组的 branches 必须是非空数组。";
            }
            for (Object branchValue : (List<?>) branchesValue) {
                String branchIssue = branchIssue(branchValue);
                if (!branchIssue.isEmpty()) {
                    return branchIssue;
                }
            }
        }
        return "";
    }

    private static String branchIssue(Object branchValue) {
        if (!(branchValue instanceof List) || ((List<?>) branchValue).isEmpty()) {
            return "规则分支必须是非空条件数组。";
        }
        for (Object conditionValue : (List<?>) branchValue) {
            String conditionIssue = conditionIssue(conditionValue);
            if (!conditionIssue.isEmpty()) {
                return conditionIssue;
            }
        }
        return "";
    }

    private static String conditionIssue(Object conditionValue) {
        if (!(conditionValue instanceof Map)) {
            return "条件必须是对象。";
        }
        Map<String, Object> condition = castMap(conditionValue);
        if (!condition.keySet().equals(CONDITION_FIELDS)) {
            Set<String> unknown = new LinkedHashSet<>(condition.keySet());
            unknown.removeAll(CONDITION_FIELDS);
            if (!unknown.isEmpty()) {
                return "条件包含未知字段：" + String.join("、", unknown) + "。";
            }
            return "条件必须只包含 field、op、value。";
        }
        Object field = condition.get("field");
        Object operator = condition.get("op");
        Object value = condition.get("value");
        if (!(field instanceof String)
                || !(operator instanceof String)
                || !(value instanceof String)
                || !KNOWN_FIELDS.contains(field)
                || !KNOWN_OPERATORS.contains(operator)) {
            return "条件包含未知字段或操作符。";
        }
        if ("app".equals(field)) {
            if (!"in".equals(operator) && !"not_in".equals(operator)) {
                return "app 条件只支持 in 或 not_in。";
            }
            return appListIssue((String) value);
        }
        if ("sender".equals(field) || "text".equals(field)) {
            if (!"regex".equals(operator) && !"not_regex".equals(operator)) {
                return field + " 条件只支持 regex 或 not_regex。";
            }
            String regexIssue = RuleCondition.regexIssue((String) value);
            return regexIssue.isEmpty()
                    ? ""
                    : field + " 条件" + regexIssue;
        }
        if (!"is".equals(operator)) {
            return field + " 条件只支持 is。";
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            return field + " 条件值必须是 true 或 false。";
        }
        return "";
    }

    private static String appListIssue(String value) {
        try {
            Object parsed = new Parser(value).parse();
            if (!(parsed instanceof List) || ((List<?>) parsed).isEmpty()) {
                return "app 条件值必须是非空包名数组。";
            }
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof String)
                        || ((String) item).trim().isEmpty()) {
                    return "app 条件值只能包含非空包名。";
                }
            }
            return "";
        } catch (IllegalArgumentException exception) {
            return "app 条件值不是合法包名数组。";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static final class Parser {
        private final String text;
        private int position;
        private int nestingDepth;

        private Parser(String text) {
            if (text == null) {
                throw new IllegalArgumentException("DSL must not be null");
            }
            this.text = text;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (position != text.length()) {
                throw invalid();
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= text.length()) {
                throw invalid();
            }
            char current = text.charAt(position);
            switch (current) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (current == '-' || Character.isDigit(current)) {
                        return parseNumber();
                    }
                    throw invalid();
            }
        }

        private Map<String, Object> parseObject() {
            enterNestedValue();
            try {
                expect('{');
                java.util.LinkedHashMap<String, Object> values =
                        new java.util.LinkedHashMap<>();
                skipWhitespace();
                if (consume('}')) {
                    return values;
                }
                while (true) {
                    skipWhitespace();
                    if (position >= text.length() || text.charAt(position) != '"') {
                        throw invalid();
                    }
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    if (values.containsKey(key)) {
                        throw invalid();
                    }
                    values.put(key, parseValue());
                    skipWhitespace();
                    if (consume('}')) {
                        return values;
                    }
                    expect(',');
                }
            } finally {
                nestingDepth--;
            }
        }

        private List<Object> parseArray() {
            enterNestedValue();
            try {
                expect('[');
                java.util.ArrayList<Object> values = new java.util.ArrayList<>();
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                while (true) {
                    values.add(parseValue());
                    skipWhitespace();
                    if (consume(']')) {
                        return values;
                    }
                    expect(',');
                }
            } finally {
                nestingDepth--;
            }
        }

        private void enterNestedValue() {
            nestingDepth++;
            if (nestingDepth > MAX_NESTING_DEPTH) {
                throw invalid();
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') {
                    return value.toString();
                }
                if (current < 0x20) {
                    throw invalid();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (position >= text.length()) {
                    throw invalid();
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(parseUnicode());
                        break;
                    default:
                        throw invalid();
                }
            }
            throw invalid();
        }

        private char parseUnicode() {
            if (position + 4 > text.length()) {
                throw invalid();
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(text.charAt(position++), 16);
                if (digit < 0) {
                    throw invalid();
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Number parseNumber() {
            int start = position;
            consume('-');
            consumeDigits();
            if (consume('.')) {
                consumeDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                consumeDigits();
            }
            try {
                return Double.valueOf(text.substring(start, position));
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }

        private void consumeDigits() {
            int start = position;
            while (position < text.length()
                    && Character.isDigit(text.charAt(position))) {
                position++;
            }
            if (position == start) {
                throw invalid();
            }
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, position)) {
                throw invalid();
            }
            position += literal.length();
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) {
                throw invalid();
            }
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < text.length()
                    && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid rule DSL");
        }
    }
}
