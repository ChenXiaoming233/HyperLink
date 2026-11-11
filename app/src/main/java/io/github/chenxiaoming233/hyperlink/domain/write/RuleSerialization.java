package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.List;

final class RuleSerialization {
    private RuleSerialization() {}

    static String serialize(List<NotificationRule> rules) {
        StringBuilder json = new StringBuilder("{\"rules\":[");
        for (int index = 0; index < rules.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            NotificationRule rule = rules.get(index);
            json.append("{\"ruleId\":");
            appendString(json, rule.ruleId());
            json.append(",\"sourceDeviceId\":");
            appendString(json, rule.sourceDeviceId());
            json.append(",\"sourceDeviceName\":");
            appendString(json, rule.sourceDeviceName());
            json.append(",\"targetDeviceId\":");
            appendString(json, rule.targetDeviceId());
            json.append(",\"targetDeviceName\":");
            appendString(json, rule.targetDeviceName());
            json.append(",\"status\":");
            appendString(json, rule.status());
            json.append(",\"summary\":");
            appendString(json, rule.summary());
            json.append(",\"dsl\":").append(rule.dslJson()).append('}');
        }
        return json.append("]}").toString();
    }

    static String quote(String value) {
        StringBuilder json = new StringBuilder();
        appendString(json, value);
        return json.toString();
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
            }
        }
        json.append('"');
    }
}
