package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;

/**
 * Where the security-posture screen gets its report, and when it was taken. The report is always
 * {@link PostureReport#discover}'s; what varies is the effective configuration it is discovered against: the
 * environment alone, or stored settings layered over it, a named tenant's included. The time is shown, since a report
 * is a snapshot.
 */
@FunctionalInterface
public interface PostureSource {

    /**
     * Collect the posture for {@code tenant}, or for the deployment alone where a deployment has no per-tenant
     * configuration to layer.
     */
    Collected collect(String tenant) throws IOException;

    /** A report and the moment it was taken. */
    record Collected(ScopedPosture report, Instant collectedAt) {

        public Collected {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(collectedAt, "collectedAt");
        }
    }

    /** The environment-only source: the effective configuration is what this process was started with. */
    static PostureSource ofEnvironment(UnaryOperator<String> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return tenant -> new Collected(
                ScopedPosture.of(PostureReport.discover(Configuration.of(configuration)), tenant),
                Instant.now());
    }
}
