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
        assertThat(handler.supportsContext(new Observation.Context())).isFalse();
    }

    private static Observation.Context named(String name) {
        Observation.Context context = new Observation.Context();
        context.setName(name);
        return context;
    }
}
