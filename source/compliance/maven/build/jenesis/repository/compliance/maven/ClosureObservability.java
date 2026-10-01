package build.jenesis.repository.compliance.maven;

import module java.base;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The publishes whose dependency closure the Maven inspector did not screen, counted on this node since it started:
 * those with no repository named to resolve through, and those whose walk failed, reached a bound or went without a
 * dependency's POM. An ALLOW over either covers what was screened, not everything the artifact pulls in.
 */
public final class ClosureObservability implements ObservabilitySource {

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.counter(ClosureResolution.UNRESOLVED,
                        "POM publishes screened without their transitive dependencies because "
                                + ClosureResolution.REPOSITORY + " names no repository and the artifact published "
                                + "no CycloneDX document beside it. Zero on a deployment that names one.",
                        ClosureResolution.unresolved(), ""),
                Metric.counter(ClosureResolution.INCOMPLETE,
                        "POM publishes screened without all of their transitive dependencies because resolving "
                                + "the closure through " + ClosureResolution.REPOSITORY + " failed, reached "
                                + ClosureResolution.DOCUMENTS + " or " + ClosureResolution.TIMEOUT + ", or found a "
                                + "dependency whose POM the repository does not hold; each is logged with the "
                                + "artifact and the reason.",
                        ClosureResolution.incomplete(), ""));
    }
}
