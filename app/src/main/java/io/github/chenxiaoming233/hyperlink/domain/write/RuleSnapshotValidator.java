package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RuleSnapshotValidator {
    private static final Set<String> KNOWN_STATUSES =
            Collections.unmodifiableSet(
                    new LinkedHashSet<>(Arrays.asList("active", "paused")));

    private RuleSnapshotValidator() {}

    static String issue(RuleStoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.unknownFieldsPresent()) {
            return "规则快照包含未知字段，为避免部分写入，当前规则写入保持只读。";
        }
        if (snapshot.unexpressibleDslPresent()) {
            return "规则快照包含不可表达 DSL，为避免部分写入，当前规则写入保持只读。";
        }
        Set<String> sourceTargetKeys = new HashSet<>();
        for (NotificationRule rule : snapshot.rules()) {
            if (!KNOWN_STATUSES.contains(rule.status())) {
                return "规则快照包含未知状态，为避免部分写入，当前规则写入保持只读。";
            }
            String sourceTargetKey =
                    rule.sourceDeviceId() + "\u0000" + rule.targetDeviceId();
            if (!sourceTargetKeys.add(sourceTargetKey)) {
                return "规则快照包含重复 source+target 规则，为避免静默删除，当前规则写入保持只读。";
            }
            if (!RuleDslValidator.isValid(rule.dslJson())) {
                return "规则快照包含未知字段或不可表达 DSL，为避免部分写入，当前规则写入保持只读。";
            }
        }
        return "";
    }
}
