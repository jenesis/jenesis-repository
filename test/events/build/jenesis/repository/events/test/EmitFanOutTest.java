package build.jenesis.repository.events.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The {@link EventSink#emit} fan-out CONTINUATION: a sink that throws must not starve a sink discovered after it.
 * Resolution is name-sorted (the shared {@code Providers} primitive orders before it creates), so {@link FailingSink}
 * ({@code failing}, throws) is always reached before {@link RecordingSink} ({@code recording}, records) whatever the
 * module path says, and the fan-out reaches the recording sink only if it keeps iterating past the earlier failure -
 * the exact behaviour a contain-then-{@code break} regression, or a containment that only covered the first sink,
 * would drop. Also pins the two commonest delivery-failure shapes: FailingSink covers IOException, an armed
 * RecordingSink covers RuntimeException. The shapes a two-arm catch could not express are
 * {@link EmitContainmentTest}'s.
 */
class EmitFanOutTest {

    private RepositoryEvent quarantine() {
        return RepositoryEvent.quarantine("maven", "com.acme:widget",
                "/maven/com/acme/widget/1.0/widget-1.0.jar", "QUARANTINE", List.of("unsigned"), Instant.now());
    }

    @Test
    void a_failing_sink_does_not_starve_a_later_recording_sink() {
        RecordingSink.RECEIVED.clear();
        RecordingSink.FAIL_WITH_RUNTIME.set(false);
        // Name order is failing, hostile, recording - so the throwing sink is reached FIRST and the recording sink is
        // only reached if emit keeps fanning out past the failure.
        assertThat(EventSink.installed()).contains("failing", "hostile", "recording");

        RepositoryEvent event = quarantine();
        assertThatCode(() -> EventSink.emit(null, event)).doesNotThrowAnyException();

        // The IOException from the earlier failing sink was swallowed AND the loop continued: the later sink got it.
        assertThat(RecordingSink.RECEIVED).containsExactly(event);
    }

    @Test
    void a_runtime_exception_from_a_sink_is_swallowed_like_an_ioexception() {
        RecordingSink.RECEIVED.clear();
        CapturingLoggerFinder.WARNINGS.clear();
        // Arm the recording sink to throw a RuntimeException, so this fan-out throws BOTH exception shapes: the
        // failing sink an IOException, the recording sink a RuntimeException. A catch narrowed to IOException would
        // let the RuntimeException escape emit here.
        RecordingSink.FAIL_WITH_RUNTIME.set(true);
        try {
            RepositoryEvent event = quarantine();
            assertThatCode(() -> EventSink.emit(null, event)).doesNotThrowAnyException();

            // The RuntimeException path is not silent either: a WARNING names the recording sink and the event.
            List<String> emitted = CapturingLoggerFinder.WARNINGS.stream()
                    .filter(line -> line.startsWith(EventSink.class.getName() + "|"))
                    .toList();
            assertThat(emitted).anyMatch(line -> line.contains("recording") && line.contains("quarantine"));
        } finally {
            RecordingSink.FAIL_WITH_RUNTIME.set(false);
        }
    }
}
