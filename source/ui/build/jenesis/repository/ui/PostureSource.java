package build.jenesis.repository.ui;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;

import module java.base;

/**
 * Where the security-posture screen gets its report, and when it was taken.
 *
 * <p>The report is always {@link PostureReport#discover}'s; what varies is the <em>effective configuration</em> it
 * is discovered against. A deployment whose settings live only in its environment answers from that; one that keeps
 * stored settings answers from those layered over it, and a tenant's own settings when a tenant is named. That is a
 * difference in where configuration comes from, not in what a posture report is, so it is a seam rather than a
 * second screen.
 *
 * <p>The collection time travels with the report because the screen says when it was taken: a posture read is a
 * snapshot of configuration, and one presented without a time reads as current when it may not be.
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
