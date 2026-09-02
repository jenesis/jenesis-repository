package build.jenesis.repository.observation.test;

import module java.base;

import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * A source that breaks the contract in the way a real one breaks it: it throws out of {@link #healthChecks()} - the
 * plugin behind it is misconfigured, its state is unreadable, its own collection blew up - instead of reporting
 * {@code UNKNOWN} the way the SPI tells it to. It is <em>not</em> {@code provides}-declared: the point is what happens
 * to the collected report, not what {@code ServiceLoader} finds.
 *
 * <p>Its message deliberately carries text a report must never echo, so a test can assert that the substitute row
 * names the failure's kind and not its message.
 */
public final class ThrowingSource implements ObservabilitySource {

    /** The message the failure carries - the report may name the exception type, never this. */
    public static final String SECRET = "credentials=hunter2 while scanning tenant acme";

    @Override
    public List<HealthCheck> healthChecks() {
        throw new IllegalStateException(SECRET);
    }
}
