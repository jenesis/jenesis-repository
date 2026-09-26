package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered {@link ObservabilitySource} for the hardening proxy leg's untrusted-upstream alarms: a
 * thin, ServiceLoader-instantiated adapter that surfaces {@link HardenedScreen}'s gateway-wide drift counter as a
 * visible signal (§9 fail-fast, errors visible). A re-fetch of an <em>immutable</em> coordinate whose bytes drifted
 * from the previously screened, digest-pinned verdict is upstream tampering: it is refused and logged loudly, and the
 * count of such alarms is reported here as {@code jenreg.gateway.hardened.drift} so an operator sees the alarm on the
 * overview, not only in a log line. Stateless - it reads the node-wide drift counter, what this node's screens have
 * recorded - so a deployment that never sets {@code harden} simply reports a zero counter.
 *
 * <p>It reports the screens' other quiet fact beside it: {@code jenreg.gateway.screen.incomplete}, the artifacts
 * served on a screen a bound stopped short of the whole body. That one is not hardened-only - both proxy legs
 * reach it through {@link ProxyScreen} - but it belongs on the same surface, because "what did this gateway serve
 * without fully screening it" is the same operator question the drift alarm answers from the other side.
 */
public final class HardeningObservability implements ObservabilitySource {

    public HardeningObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.counter("jenreg.gateway.hardened.drift",
                        "Upstream drift alarms - re-fetches of an immutable coordinate whose bytes changed under it "
                                + "(different digest than the previously screened, pinned verdict), refused as tampering rather "
                                + "than served. A non-zero, rising count is a compromise indicator for the upstream.",
                        HardenedScreen.driftEvents(), ""),
                Metric.counter("jenreg.gateway.screen.incomplete",
                        "Artifacts served after a screen a bound stopped short of the whole body - some claiming "
                                + "inspector reported that it could not read to the end (a byte, entry, finding or "
                                + "nesting ceiling, or a container it could not decode) and the gate found nothing to "
                                + "withhold in the part that WAS read. Such an ALLOW covers only what was screened, so "
                                + "a rising count says the deployment routinely serves artifacts larger than its "
                                + "inspection tiers - the signal for raising a tier.",
                        ProxyScreen.incompleteScreens(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        long drift = HardenedScreen.driftEvents();
        if (drift == 0) {
            return List.of(HealthCheck.up("jenreg.gateway.hardened.drift",
                    "No upstream drift detected: no immutable coordinate has served bytes differing from its pinned "
                            + "screening verdict."));
        }
        return List.of(HealthCheck.of("jenreg.gateway.hardened.drift",
                "Upstream drift detected and refused: an immutable coordinate served bytes differing from its pinned "
                        + "screening verdict - a compromise indicator for the upstream.",
                Health.DEGRADED,
                drift + " drift alarm(s) raised since start"));
    }
}
