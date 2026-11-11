package io.github.chenxiaoming233.hyperlink.domain.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class CapabilityProbeTest {
    @Test
    public void acceptsCompleteCapabilitiesForTestedVersion() {
        CapabilityReport report =
                CapabilityProbe.evaluate(
                        CapabilityEvidence.demoComplete(
                                "demo-version-tested", Set.of("demo-version-tested")));

        assertTrue(report.passed());
        assertFalse(report.compatibilityUnverified());
        assertTrue(report.gaps().isEmpty());
    }

    @Test
    public void unknownVersionIsAllowedButMarkedUnverified() {
        CapabilityReport report =
                CapabilityProbe.evaluate(
                        CapabilityEvidence.demoComplete(
                                "demo-version-unknown", Set.of("demo-version-tested")));

        assertTrue(report.passed());
        assertTrue(report.compatibilityUnverified());
    }

    @Test
    public void everyCapabilityCategoryIsRequired() {
        CapabilityEvidence complete = CapabilityEvidence.demoComplete();
        for (CapabilityKind kind : CapabilityKind.values()) {
            String missing = complete.observed(kind).iterator().next();
            CapabilityReport report = CapabilityProbe.evaluate(complete.without(kind, missing));

            assertFalse(kind.name(), report.passed());
            assertEquals(kind.name(), 1, report.gaps().size());
            assertEquals(kind, report.gaps().get(0).kind());
            assertEquals(missing, report.gaps().get(0).descriptor());
        }
    }

    @Test
    public void dslShapeIsPartOfTheProbe() {
        CapabilityEvidence withoutDsl =
                CapabilityEvidence.demoComplete()
                        .without(CapabilityKind.DSL_STRUCTURE, "andGroups");

        CapabilityReport report = CapabilityProbe.evaluate(withoutDsl);

        assertFalse(report.passed());
        assertTrue(report.readOnlyMessage().contains("DSL"));
    }

    @Test
    public void allEligibleNotificationsDslIsPartOfTheProbe() {
        CapabilityEvidence withoutMode =
                CapabilityEvidence.demoComplete()
                        .without(CapabilityKind.DSL_STRUCTURE, "mode");

        assertFalse(CapabilityProbe.evaluate(withoutMode).passed());
    }
}
