package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CapabilityEvidence {
    private final String voiceAssistVersion;
    private final Set<String> testedVersions;
    private final Map<CapabilityKind, Set<String>> observed;

    public CapabilityEvidence(
            String voiceAssistVersion,
            Set<String> testedVersions,
            Map<CapabilityKind, Set<String>> observed) {
        this.voiceAssistVersion = requireText(voiceAssistVersion, "voiceAssistVersion");
        this.testedVersions = immutableCopy(testedVersions);
        this.observed = immutableMapCopy(observed);
    }

    public static CapabilityEvidence demoComplete() {
        return demoComplete(
                "demo-version-tested", Collections.singleton("demo-version-tested"));
    }

    public static CapabilityEvidence demoComplete(
            String voiceAssistVersion, Set<String> testedVersions) {
        return new CapabilityEvidence(
                voiceAssistVersion, testedVersions, CapabilityProbe.requiredCapabilities());
    }

    public CapabilityEvidence without(CapabilityKind kind, String descriptor) {
        Map<CapabilityKind, Set<String>> copy = copyMap(observed);
        Set<String> values =
                new LinkedHashSet<>(copy.getOrDefault(kind, Collections.emptySet()));
        values.remove(descriptor);
        copy.put(kind, values);
        return new CapabilityEvidence(voiceAssistVersion, testedVersions, copy);
    }

    public String voiceAssistVersion() {
        return voiceAssistVersion;
    }

    public Set<String> testedVersions() {
        return testedVersions;
    }

    public Set<String> observed(CapabilityKind kind) {
        return observed.getOrDefault(kind, Collections.emptySet());
    }

    private static Set<String> immutableCopy(Set<String> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            copy.add(requireText(value, "testedVersion"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<CapabilityKind, Set<String>> immutableMapCopy(
            Map<CapabilityKind, Set<String>> values) {
        Objects.requireNonNull(values, "observed");
        Map<CapabilityKind, Set<String>> copy = copyMap(values);
        EnumMap<CapabilityKind, Set<String>> immutable = new EnumMap<>(CapabilityKind.class);
        for (Map.Entry<CapabilityKind, Set<String>> entry : copy.entrySet()) {
            immutable.put(entry.getKey(), immutableCopy(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<CapabilityKind, Set<String>> copyMap(
            Map<CapabilityKind, Set<String>> values) {
        EnumMap<CapabilityKind, Set<String>> copy = new EnumMap<>(CapabilityKind.class);
        for (Map.Entry<CapabilityKind, Set<String>> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "capability kind"),
                    new LinkedHashSet<>(Objects.requireNonNull(entry.getValue(), "capabilities")));
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
