package build.jenesis.repository.test;

import build.jenesis.repository.server.LoggingObservationHandler;
import io.micrometer.observation.Observation;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logging handler writes one line per operation of this server and one per HTTP request, and none for the
 * observations Boot and Spring Security raise around them: those are several per request, and logging them all
 * multiplied the log by five under load.
 */
public class LoggingObservationHandlerTest {

    private final LoggingObservationHandler handler = new LoggingObservationHandler();

    @Test
    void the_servers_own_operations_and_the_request_line_are_logged() {
        assertThat(handler.supportsContext(named("jenreg.deploy"))).isTrue();
        assertThat(handler.supportsContext(named("jenreg.proxy.fetch"))).isTrue();
        assertThat(handler.supportsContext(named("http.server.requests"))).isTrue();
    }

    @Test
    void the_frameworks_per_request_observations_are_not() {
        assertThat(handler.supportsContext(named("spring.security.filterchains"))).isFalse();
        assertThat(handler.supportsContext(named("spring.security.authorizations"))).isFalse();
        assertThat(handler.supportsContext(named("spring.security.http.secured.requests"))).isFalse();
    }

    /** An observation created from a convention is named at start, after the handler was asked; it is admitted
     *  then and its line decided once it stops, so the request line is not lost to the order of the two calls. */
    @Test
    void an_observation_not_yet_named_is_admitted_and_decided_when_it_stops() {
        assertThat(handler.supportsContext(new Observation.Context())).isTrue();
    }

    private static Observation.Context named(String name) {
        Observation.Context context = new Observation.Context();
        context.setName(name);
        return context;
    }
}
