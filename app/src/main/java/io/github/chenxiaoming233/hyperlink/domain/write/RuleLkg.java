package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuleLkg {
    private final List<NotificationRule> rules;
    private final List<String> orderedRuleIds;
    private final Map<String, String> filtersByRuleId;
    private final Map<String, String> statusesByRuleId;
    private final Map<String, Boolean> targetAuthorizations;
    private final NotificationAccessState notificationAccess;
    private final String serializedJson;

    private RuleLkg(
            List<NotificationRule> rules,
            List<String> orderedRuleIds,
            Map<String, String> filtersByRuleId,
            Map<String, String> statusesByRuleId,
            Map<String, Boolean> targetAuthorizations,
            NotificationAccessState notificationAccess,
            String serializedJson) {
        this.rules = immutableList(rules);
        this.orderedRuleIds = immutableList(orderedRuleIds);
        this.filtersByRuleId = immutableMap(filtersByRuleId);
        this.statusesByRuleId = immutableMap(statusesByRuleId);
        this.targetAuthorizations = immutableMap(targetAuthorizations);
        this.notificationAccess =
                Objects.requireNonNull(notificationAccess, "notificationAccess");
        this.serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
    }

    public static RuleLkg capture(RuleStoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> ruleIds = new ArrayList<>();
        Map<String, String> filters = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (NotificationRule rule : snapshot.flowSnapshot().rules()) {
            ruleIds.add(rule.ruleId());
            filters.put(rule.ruleId(), rule.dslJson());
            statuses.put(rule.ruleId(), rule.status());
        }
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            permissions.put(target.deviceId(), target.targetAuthorized());
        }
        return new RuleLkg(
                snapshot.flowSnapshot().rules(),
                ruleIds,
                filters,
                statuses,
                permissions,
                snapshot.flowSnapshot().notificationAccess(),
                snapshot.serializedJson());
    }

    public List<NotificationRule> rules() {
        return rules;
    }

    public List<String> orderedRuleIds() {
        return orderedRuleIds;
    }

    public Map<String, String> filtersByRuleId() {
        return filtersByRuleId;
    }

    public Map<String, String> statusesByRuleId() {
        return statusesByRuleId;
    }

    public Map<String, Boolean> targetAuthorizations() {
        return targetAuthorizations;
    }

    public NotificationAccessState notificationAccess() {
        return notificationAccess;
    }

    public String serializedJson() {
        return serializedJson;
    }

    public boolean permissionsMatch(RuleStoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.flowSnapshot().notificationAccess() != notificationAccess) {
            return false;
        }
        Map<String, Boolean> current = new LinkedHashMap<>();
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            current.put(target.deviceId(), target.targetAuthorized());
        }
        return targetAuthorizations.equals(current);
    }

    private static <T> List<T> immutableList(List<T> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, "value"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "key"),
                    Objects.requireNonNull(entry.getValue(), "value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
