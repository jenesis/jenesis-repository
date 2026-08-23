package build.jenesis.repository.server;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The logging pillar of the Observation API, in one place beside {@link Observations}: it logs each of this server's
 * observed operations ({@code jenreg.*}) and each HTTP request once it completes, with the observation name, its
 * key-values (repository, tenant, any outcome) and any error. Registered once as a bean by the free auto-configuration, so a single handler lights logging for every
 * {@code jenreg.*} operation wherever the server module runs - the console, the maintenance sweep, the downstream
 * controllers - instead of each module carrying its own copy. Boot's observation auto-configuration attaches it to
 * the auto-configured {@code ObservationRegistry} alongside the metrics handler (Micrometer, exposed through
 * Actuator) and a tracing handler (when a tracing bridge is on the path), so one instrumentation point feeds
 * logging, metrics and tracing; when tracing is on the log line carries the trace and span ids automatically.
 */
public final class LoggingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger LOGGER = LoggerFactory.getLogger("build.jenesis.observation");

    /** The one line per HTTP request that is the server's access log - kept beside its own operations. */
    private static final String REQUEST = "http.server.requests";

    /** This server's own operations ({@code jenreg.*}) and the request line. Boot's and Spring Security's own
     *  observations (a filter-chain position, an authorization decision - several per request) are metrics and
     *  traces, not log lines: logging them all wrote four lines of noise for every request served. An observation
     *  created from a convention is named only when it starts, after this is asked, so an unnamed context is
     *  accepted here and decided in {@link #onStop}. */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context.getName() == null || logged(context.getName());
    }

    private static boolean logged(String name) {
        return name != null && (name.startsWith("jenreg.") || name.equals(REQUEST));
    }

    @Override
    public void onStop(Observation.Context context) {
        if (!logged(context.getName())) {
            return;
        }
        if (context.getError() == null) {
            LOGGER.info("{} {}", context.getName(), context.getAllKeyValues());
        } else {
            LOGGER.warn("{} {} failed: {}", context.getName(), context.getAllKeyValues(),
                    context.getError().toString());
        }
    }
}
