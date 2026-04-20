package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.runtime.PulseRuntimeMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four shipped presets ({@code dev}, {@code prod}, {@code test}, {@code canary}) are the
 * adoption shortcut: a user types one {@code spring.config.import} line and gets a Pulse profile
 * tuned for that environment. These tests pin the contract of each preset by booting an isolated
 * context with the preset loaded and asserting on the resulting {@link PulseProperties}.
 *
 * <p>The point is not exhaustive coverage — it's that bumping a preset value shows up as a test
 * failure so we can update docs and release notes deliberately.
 */
class PulseProfilePresetsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PulseProperties.class)
    static class EnableProps {}

    private ApplicationContextRunner withPreset(String preset) {
        Resource yaml = new ClassPathResource("META-INF/pulse/" + preset + ".yml");
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("pulse-" + preset, yaml);
            return runner.withInitializer(
                    ctx -> sources.forEach(ctx.getEnvironment().getPropertySources()::addFirst));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load preset " + preset, e);
        }
    }

    @Test
    void dev_preset_runs_enforcing_with_full_sampling_and_loose_cardinality_budget() {
        withPreset("dev").run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.runtime().mode()).isEqualTo(PulseRuntimeMode.Mode.ENFORCING);
            assertThat(props.sampling().probability()).isEqualTo(1.0);
            assertThat(props.traceGuard().failOnMissing()).isFalse();
            assertThat(props.cardinality().maxTagValuesPerMeter()).isEqualTo(5000);
            assertThat(props.banner().enabled()).isTrue();
        });
    }

    @Test
    void prod_preset_runs_enforcing_with_low_sampling_and_strict_guards() {
        withPreset("prod").run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.runtime().mode()).isEqualTo(PulseRuntimeMode.Mode.ENFORCING);
            assertThat(props.sampling().probability()).isEqualTo(0.10);
            assertThat(props.cardinality().maxTagValuesPerMeter()).isEqualTo(1000);
            assertThat(props.banner().enabled()).isFalse();
            assertThat(props.tenant().maxTagCardinality()).isEqualTo(100);
            assertThat(props.dependencies().fanOutWarnThreshold()).isEqualTo(20);
        });
    }

    @Test
    void test_preset_disables_enforcing_features_so_unit_tests_stay_clean() {
        withPreset("test").run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.traceGuard().enabled()).isFalse();
            assertThat(props.cardinality().enabled()).isFalse();
            assertThat(props.timeoutBudget().enabled()).isFalse();
            assertThat(props.sampling().probability()).isEqualTo(0.0);
            assertThat(props.banner().enabled()).isFalse();
            assertThat(props.db().enabled()).isFalse();
        });
    }

    @Test
    void canary_preset_runs_dry_run_with_full_sampling() {
        withPreset("canary").run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.runtime().mode())
                    .as("canary must be DRY_RUN — every guardrail observes, none enforces")
                    .isEqualTo(PulseRuntimeMode.Mode.DRY_RUN);
            assertThat(props.sampling().probability()).isEqualTo(1.0);
            assertThat(props.traceGuard().failOnMissing())
                    .as("fail-on-missing stays enabled — DRY_RUN downgrades it at runtime")
                    .isTrue();
        });
    }

    @Test
    void all_presets_pass_jsr_380_validation() {
        for (String preset : new String[] {"dev", "prod", "test", "canary"}) {
            withPreset(preset).run(ctx -> assertThat(ctx)
                    .as("preset '%s' must bind cleanly", preset)
                    .hasNotFailed());
        }
    }
}
