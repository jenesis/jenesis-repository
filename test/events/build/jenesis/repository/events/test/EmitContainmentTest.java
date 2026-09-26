package build.jenesis.repository.events.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link EventSink#emit} contains and what it deliberately does not.
 *
 * <p>The old containment was {@code catch (IOException | RuntimeException)}, which left two escapes and made a third
 * one possible from inside its own handler. This suite states the ruling on each, because "contained" is not one
 * answer for every throwable a sink can raise:
 *
 * <ol>
 * <li>A checked exception a sink smuggles past its {@code throws} clause is a <em>delivery</em> failure like any
 *     other and is contained, with the WARN naming it - the old catch let it through untouched.</li>
 * <li>An {@link Error} is not a delivery failure at all. It says the JVM or the module graph gave way, so it is
 *     attributed to the sink and rethrown - the same ruling a contract kit follows, where an {@code Error}
 *     is reported as the harness breaking rather than filed as the subject's answer. Containing it would leave a
 *     deployment running on a broken runtime with a queued webhook as the only evidence.</li>
 * <li>A sink whose {@code name()} throws no longer defeats the containment: the fan-out reads every name at
 *     resolution, before the first delivery, so the diagnostic can never re-enter a broken sink - and a sink that
 *     cannot say what it is called is a packaging error refused loudly rather than a notification quietly lost.</li>
 * </ol>
 *
 * <p>{@link HostileSink} sorts between {@link FailingSink} and {@link RecordingSink}, so every leg below can also
 * state whether the sink <em>after</em> the misbehaving one was reached. That is the observable difference between
 * containment (the fan-out continues) and propagation (it does not), and asserting it is what keeps this suite from
 * passing on a fan-out that silently stopped early.
 */
class EmitContainmentTest {

    private RepositoryEvent finding() {
        return RepositoryEvent.finding("maven", "com.acme:widget", "1.0", "osv", "GHSA-xxxx", "HIGH", "vulnerability",
                Instant.now());
    }

    @BeforeEach
    void arm() {
        RecordingSink.RECEIVED.clear();
        RecordingSink.FAIL_WITH_RUNTIME.set(false);
        CapturingLoggerFinder.WARNINGS.clear();
    }

    @AfterEach
    void disarm() {
        HostileSink.MODE.set(HostileSink.Mode.OFF);
    }

    @Test
    void a_checked_exception_smuggled_past_the_throws_clause_is_contained_like_an_ioexception() {
        HostileSink.MODE.set(HostileSink.Mode.SNEAKY);
        RepositoryEvent event = finding();

        // catch (IOException | RuntimeException) matched neither, so this escaped emit and failed the finding write.
        assertThatCode(() -> EventSink.emit(null, event)).doesNotThrowAnyException();

        assertThat(diagnostics())
                .as("the contained failure is named rather than silent, and it is named as a dropped finding")
                .anyMatch(line -> line.contains(HostileSink.NAME) && line.contains("finding")
                        && line.contains("com.acme:widget@1.0"));
        assertThat(RecordingSink.RECEIVED)
                .as("and the sink after it still got the event - containment continues the fan-out")
                .containsExactly(event);
    }

    @Test
    void an_error_from_a_sink_is_attributed_and_rethrown_rather_than_contained() {
        HostileSink.MODE.set(HostileSink.Mode.ERROR);
        RepositoryEvent event = finding();

        assertThatThrownBy(() -> EventSink.emit(null, event))
                .as("an Error is the runtime breaking, not a notification failing to queue: it must reach the caller")
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessageContaining("build.jenesis.repository.delivery.Missing");

        assertThat(diagnostics())
                .as("but it is attributed on the way out, so the broken sink is named rather than left to a bare "
                        + "stack trace in a publish's 500")
                .anyMatch(line -> line.contains(HostileSink.NAME) && line.contains("finding")
                        && line.contains("Error"));
        assertThat(RecordingSink.RECEIVED)
                .as("and the sinks after it are starved deliberately - there is no useful fan-out left to continue "
                        + "once the runtime has given way")
                .isEmpty();
    }

    @Test
    void a_sink_whose_name_throws_fails_at_resolution_instead_of_defeating_the_containment() {
        HostileSink.MODE.set(HostileSink.Mode.HOSTILE_NAME);
        RepositoryEvent event = finding();

        assertThatThrownBy(() -> EventSink.emit(null, event))
                .as("a sink that cannot say what it is called is a packaging error, refused where every other "
                        + "packaging error is refused")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot even say what it is called");

        assertThat(RecordingSink.RECEIVED)
                .as("resolution happens before delivery, so no sink was called at all - which is exactly why the "
                        + "handler can no longer be re-entered through a broken name()")
                .isEmpty();
        assertThatThrownBy(EventSink::installed)
                .as("and the capability surface refuses the same packaging error rather than reporting a set with a "
                        + "hole in it")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_null_event_is_refused_before_any_sink_or_diagnostic_sees_it() {
        assertThatThrownBy(() -> EventSink.emit(null, null))
                .as("a null event is a producer bug: refusing it here keeps a NullPointerException out of the "
                        + "diagnostic that exists to report failures")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
        assertThat(RecordingSink.RECEIVED).isEmpty();
    }

    /** The lines this SPI logged, WARN or ERROR, in arrival order. */
    private static List<String> diagnostics() {
        return CapturingLoggerFinder.WARNINGS.stream()
                .filter(line -> line.startsWith(EventSink.class.getName() + "|"))
                .toList();
    }
}
