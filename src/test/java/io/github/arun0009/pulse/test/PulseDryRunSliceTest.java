package io.github.arun0009.pulse.test;

import io.github.arun0009.pulse.runtime.PulseRuntimeMode;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link PulseDryRun} contract: the meta-annotation must add a
 * {@code pulse.runtime.mode=DRY_RUN} test property so that a Pulse-aware test class flips into
 * observe-only mode at startup. Without this guarantee, future refactors could silently turn the
 * slice into a no-op.
 *
 * <p>The actual runtime-mode wiring (auto-configuration registers a {@link PulseRuntimeMode} bean
 * seeded from the {@code pulse.runtime.mode} property) is exercised by
 * {@code PulseAutoConfigurationTest} and {@code PulseRuntimeModeWiringTest}; this test only
 * guarantees the slice annotation actually contributes the property.
 */
class PulseDryRunSliceTest {

    @Test
    void dry_run_annotation_is_marked_with_test_property_source() {
        TestPropertySource source = AnnotationUtils.findAnnotation(PulseDryRun.class, TestPropertySource.class);
        assertThat(source)
                .as("@PulseDryRun must carry a @TestPropertySource so JUnit/Spring picks up the override")
                .isNotNull();
        assertThat(Arrays.asList(source.properties()))
                .as("the property must flip the runtime mode to DRY_RUN")
                .contains("pulse.runtime.mode=DRY_RUN");
    }

    @Test
    void dry_run_property_round_trips_into_runtime_mode_enum() {
        PulseRuntimeMode mode = new PulseRuntimeMode(PulseRuntimeMode.Mode.valueOf("DRY_RUN"));
        assertThat(mode.dryRun()).isTrue();
        assertThat(mode.enforcing()).isFalse();
        assertThat(mode.off()).isFalse();
    }
}
