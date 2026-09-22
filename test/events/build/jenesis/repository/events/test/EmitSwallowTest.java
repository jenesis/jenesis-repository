package build.jenesis.repository.events.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The best-effort {@link EventSink#emit} seam under a failing discovered sink: it must swallow the failure so the
 * observed operation never fails, but must not swallow it <em>silently</em> - a WARNING names the lost event
 * (PRINCIPLES §9: a fail-soft still emits a diagnostic). {@link FailingSink} always throws and {@link
 * CapturingLoggerFinder} captures the diagnostic, both discovered for this module only.
 */
class EmitSwallowTest {

    @Test
    void a_failing_sink_is_swallowed_but_a_diagnostic_names_the_lost_event() {
        CapturingLoggerFinder.WARNINGS.clear();
        RepositoryEvent event = RepositoryEvent.quarantine("maven", "com.acme:widget",
                "/maven/com/acme/widget/1.0/widget-1.0.jar", "QUARANTINE", List.of("unsigned"), Instant.now());

        // The failing sink is discovered, so emit actually exercises the swallow path.
        assertThat(EventSink.installed()).contains("failing");

        // Best-effort: emit must never propagate the sink's failure to the operation it observes.
        assertThatCode(() -> EventSink.emit(null, event)).doesNotThrowAnyException();

        // ...but it must not vanish: a WARNING names the sink, the event kind and the coordinate/path.
        List<String> emitted = CapturingLoggerFinder.WARNINGS.stream()
                .filter(line -> line.startsWith(EventSink.class.getName() + "|"))
                .toList();
        assertThat(emitted).isNotEmpty();
        assertThat(emitted.getFirst())
                .contains("failing")
                .contains("quarantine")
                .contains("com.acme:widget")
                .contains("path=/maven/com/acme/widget/1.0/widget-1.0.jar");
    }
}
