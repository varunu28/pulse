package io.github.arun0009.pulse.test;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test slice that boots Pulse in {@link io.github.arun0009.pulse.runtime.PulseRuntimeMode.Mode#DRY_RUN
 * DRY_RUN} for the duration of the test class.
 *
 * <p>Use this when you want to <em>regression-test the things Pulse would catch in production</em>
 * without paying the cost of Pulse actually rejecting requests in the test harness:
 *
 * <ul>
 *   <li>The trace-context guard records {@code pulse.trace.missing} but never throws — so a test
 *       calling an endpoint without {@code traceparent} can still assert that Pulse <em>noticed</em>.
 *   <li>The cardinality firewall increments {@code pulse.cardinality.overflow} but lets the
 *       runaway tag value pass through — so a test that intentionally generates 5000 distinct
 *       userIds can assert "my new endpoint is leaking userId into a counter" without the test
 *       getting bucketed metrics.
 *   <li>Every other Pulse subsystem stays on its normal code path.
 * </ul>
 *
 * <p>Combine with {@link PulseTest} to get the standard Pulse test slice (in-memory OTel, harness)
 * plus dry-run mode:
 *
 * <pre>{@code
 * @PulseTest
 * @PulseDryRun
 * class TraceContextRegressionTest {
 *
 *     @Autowired PulseTestHarness pulse;
 *     @Autowired TestRestTemplate http;
 *
 *     @Test
 *     void calling_orders_without_traceparent_increments_pulse_trace_missing() {
 *         http.getForEntity("/orders/42", String.class);
 *         assertThat(pulse.counterValue("pulse.trace.missing", "route", "/orders/{id}"))
 *             .isEqualTo(1.0);
 *     }
 * }
 * }</pre>
 *
 * <p>This is a meta-annotation that adds a single test property; it does not import any beans, so
 * it composes cleanly with any Pulse test annotation, {@code @WebMvcTest}, {@code @DataJpaTest},
 * etc.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = "pulse.runtime.mode=DRY_RUN")
public @interface PulseDryRun {}
