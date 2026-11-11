package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CapabilityProbe {
    private static final Map<CapabilityKind, Set<String>> REQUIRED =
            createRequirements();

    private CapabilityProbe() {}

    public static CapabilityReport evaluate(CapabilityEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        List<CapabilityGap> gaps = new ArrayList<>();
        for (CapabilityKind kind : CapabilityKind.values()) {
            for (String descriptor : REQUIRED.getOrDefault(kind, Collections.emptySet())) {
                if (!evidence.observed(kind).contains(descriptor)) {
                    gaps.add(new CapabilityGap(kind, descriptor));
                }
            }
        }
        boolean unknownVersion =
                !evidence.testedVersions().contains(evidence.voiceAssistVersion());
        return new CapabilityReport(gaps, unknownVersion);
    }

    static Map<CapabilityKind, Set<String>> requiredCapabilities() {
        return REQUIRED;
    }

    private static Map<CapabilityKind, Set<String>> createRequirements() {
        EnumMap<CapabilityKind, Set<String>> requirements =
                new EnumMap<>(CapabilityKind.class);
        requirements.put(
                CapabilityKind.CLASS,
                setOf("rule.store", "rule.model", "target.permission-store"));
        requirements.put(
                CapabilityKind.CONSTRUCTOR,
                setOf("rule.store.constructor", "rule.model.constructor"));
        requirements.put(
                CapabilityKind.METHOD,
                setOf(
                        "rule.store.read",
                        "rule.store.write",
                        "rule.serializer.parse",
                        "target.permission-store.read"));
        requirements.put(
                CapabilityKind.FIELD,
                setOf("rule.store.rules", "target.permission-store.targets"));
        requirements.put(CapabilityKind.STATE_VALUE, setOf("active", "paused"));
        requirements.put(
                CapabilityKind.DSL_STRUCTURE,
                setOf(
                        "andGroups",
                        "branches",
                        "field",
                        "op",
                        "value",
                        "mode",
                        "allEligibleNotifications",
                        "ruleText"));
        return immutableMap(requirements);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static Map<CapabilityKind, Set<String>> immutableMap(
            Map<CapabilityKind, Set<String>> values) {
        EnumMap<CapabilityKind, Set<String>> copy = new EnumMap<>(CapabilityKind.class);
        copy.putAll(values);
        return Collections.unmodifiableMap(copy);
    }
}
