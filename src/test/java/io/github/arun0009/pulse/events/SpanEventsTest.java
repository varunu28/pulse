package io.github.arun0009.pulse.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wide-event API is the README's most strategic claim — one call must produce a span event AND
 * a counter increment AND a structured log line. These tests cover the first two end-to-end (the
 * log goes through Log4j2 and is exercised in the integration test).
 */
class SpanEventsTest {

    private static final WideEventsProperties DEFAULT_CONFIG =
            new WideEventsProperties(true, true, true, "pulse.events", "event");

    private InMemorySpanExporter exporter;
    private Tracer tracer;
    private io.micrometer.tracing.Tracer micrometerTracer;
    private SimpleMeterRegistry registry;
    private SpanEvents events;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("pulse-test");
        // Bridge the OTel SDK to a Micrometer Tracer, mirroring what Spring Boot does at runtime,
        // so SpanEvents (which talks to Micrometer's Tracer API) sees the same active span the
        // test makes current via OTel's Scope.
        micrometerTracer = new OtelTracer(tracer, new OtelCurrentTraceContext(), e -> {});
        registry = new SimpleMeterRegistry();
        events = new SpanEvents(
                registry, DEFAULT_CONFIG, io.micrometer.observation.ObservationRegistry.NOOP, micrometerTracer);
    }

    @Test
    void emit_attaches_event_to_active_span_and_increments_counter() {
        Span span = tracer.spanBuilder("OrderService.place").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            events.emit(
                    "order.placed",
                    Map.of(
                            "orderId", "ord-123",
                            "amount", 49.99,
                            "currency", "USD"));
        } finally {
            span.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData recorded = spans.get(0);

        List<EventData> spanEvents = recorded.getEvents();
        assertThat(spanEvents).hasSize(1);
        assertThat(spanEvents.get(0).getName()).isEqualTo("order.placed");

        // TODO(phase 4e): per-event attributes — Micrometer's Span has no attribute-bearing
        // event() overload, so until the Observation refactor lands, attributes land as span tags
        // rather than event attributes. Restore the per-event attribute assertions once 4e lands.
        assertThat(recorded.getAttributes().asMap())
                .extractingByKey(io.opentelemetry.api.common.AttributeKey.stringKey("orderId"))
                .isEqualTo("ord-123");
        assertThat(recorded.getAttributes().asMap())
                .extractingByKey(io.opentelemetry.api.common.AttributeKey.doubleKey("amount"))
                .isEqualTo(49.99);

        assertThat(registry.find("pulse.events")
                        .tag("event", "order.placed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void counter_uses_only_event_name_so_high_cardinality_attributes_do_not_leak_into_metrics() {
        // We deliberately do NOT make a span current here — we want to assert the counter is
        // safe even when attributes vary wildly. With a current span and the Phase-4a fidelity
        // model, attribute values would land as span tags (one per emit), but the counter must
        // remain a single series regardless. Wrapping in a span is unnecessary for the metric
        // assertion.
        Span span = tracer.spanBuilder("OrderService.place").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            for (int i = 0; i < 100; i++) {
                events.emit("payment.captured", Map.of("paymentId", "pay-" + i, "amount", 10));
            }
        } finally {
            span.end();
        }

        long distinctCounters = registry.find("pulse.events").counters().stream()
                .filter(c -> "payment.captured".equals(c.getId().getTag("event")))
                .count();
        assertThat(distinctCounters)
                .as("counter must have exactly one series per event name regardless of attributes")
                .isEqualTo(1);
        assertThat(registry.find("pulse.events")
                        .tag("event", "payment.captured")
                        .counter()
                        .count())
                .isEqualTo(100.0);
    }

    @Test
    void disabled_subsystem_emits_nothing() {
        SpanEvents disabled = new SpanEvents(
                registry,
                new WideEventsProperties(false, true, true, "pulse.events", "event"),
                io.micrometer.observation.ObservationRegistry.NOOP,
                micrometerTracer);
        Span span = tracer.spanBuilder("X").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            disabled.emit("never.fired");
        } finally {
            span.end();
        }
        assertThat(registry.find("pulse.events").counter()).isNull();
        assertThat(exporter.getFinishedSpanItems().get(0).getEvents()).isEmpty();
    }

    @Test
    void counter_disabled_still_records_span_event() {
        SpanEvents noCounter = new SpanEvents(
                registry,
                new WideEventsProperties(true, false, true, "pulse.events", "event"),
                io.micrometer.observation.ObservationRegistry.NOOP,
                micrometerTracer);
        Span span = tracer.spanBuilder("X").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            noCounter.emit("trace.only");
        } finally {
            span.end();
        }
        assertThat(exporter.getFinishedSpanItems().get(0).getEvents()).hasSize(1);
        assertThat(registry.find("pulse.events").counter()).isNull();
    }

    @Test
    void emit_without_active_span_still_increments_counter() {
        events.emit("background.tick");
        assertThat(registry.find("pulse.events")
                        .tag("event", "background.tick")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
