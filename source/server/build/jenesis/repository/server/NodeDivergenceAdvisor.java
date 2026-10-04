package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

/**
 * The "node divergence" security-posture advisor: it reads every node's published
 * {@link NodeFingerprint} from the shared store, runs the {@link ConsistencyReport consistency check}, and raises one
 * advisory per {@link NodeDivergence stuck divergence} - each with the <em>why</em> (which node, how far behind, for how
 * long) and the fix (check the sweep lease, reconcile the config), so a wedged node surfaces on the same
 * {@code GET /api/posture} / console / boot-log posture surfaces every other configuration warning does. It is
 * {@code provides}-declared, discovered with {@link java.util.ServiceLoader} like the core {@code SecurityPosture}
 * seed.
 *
 * <p>It reads no store: the report is the one this node's {@link NodeFingerprintPublisher} computed on its last
 * heartbeat over the deployment's own store and {@linkplain #observe handed} here, so a posture read costs nothing
 * and stands whatever the store is doing. It <strong>degrades cleanly</strong>: a node that publishes no fingerprint,
 * a single-node deployment and one whose nodes agree all report nothing, so the posture surface never advises about
 * divergence that is not happening. The advisory names the risk, never a resolved hash or a config value, so this
 * surface cannot leak one.
 */
public final class NodeDivergenceAdvisor implements SafetyAdvisor {

    static final String DOCS = "https://jenesis.build/repository/operations/";

    /** The report this node's heartbeat last computed, or {@code null} while none publishes. */
    private static final AtomicReference<ConsistencyReport> OBSERVED = new AtomicReference<>();

    /** Record the report a heartbeat computed, which the next posture read advises from. */
    static void observe(ConsistencyReport report) {
        OBSERVED.set(Objects.requireNonNull(report, "report"));
    }

    /** Forget the observed report, as the publisher that recorded it stops. */
    static void forget() {
        OBSERVED.set(null);
    }

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        ConsistencyReport report = OBSERVED.get();
        if (report == null || report.converged()) {
            return List.of();
        }
        List<SecurityAdvisory> advisories = new ArrayList<>();
        for (NodeDivergence divergence : report.divergences()) {
            advisories.add(advisory(divergence));
        }
        return advisories;
    }

    /** Map one divergence to its posture advisory: a stuck cursor is an operational WARN (fix the sweep), while a
     *  config or pointer split is a CRITICAL - the fleet disagrees on what must be identical. */
    public static SecurityAdvisory advisory(NodeDivergence divergence) {
        return switch (divergence.kind()) {
            case STUCK_CURSOR -> SecurityAdvisory.deployment("jenrepo.consistency.stuck", Severity.WARN,
                    "Node " + divergence.nodeId() + " is stuck behind the fleet",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". It is alive (still heartbeating) but "
                            + "its derived-index cursor has not advanced past the configured sweep-interval budget, so "
                            + "it is serving a stale view rather than merely lagging.",
                    "Check that node's index-sweep lease and worker: a wedged sweep or a lost lease keeps it from "
                            + "converging. Widen jenrepo.consistency.sweep-intervals only if the fleet legitimately "
                            + "needs longer to catch up.",
                    "jenrepo.consistency.sweep-intervals", "3", DOCS + "#jenrepo.consistency.stuck");
            case CONFIG_MISMATCH -> SecurityAdvisory.deployment("jenrepo.consistency.config", Severity.CRITICAL,
                    "Node " + divergence.nodeId() + " disagrees on the deployment config or tenant set",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". Config and the tenant set must be "
                            + "identical on every node; a live node on a different generation missed a config or "
                            + "tenant change or is split from the others, so it enforces different settings than "
                            + "the fleet.",
                    "Reconcile this node's configuration and tenant view with the fleet and confirm it reloaded - "
                            + "restart it if it did not. A persistent split points at a lost lease or a partitioned "
                            + "store.",
                    "", "", DOCS + "#jenrepo.consistency.config");
            case POINTER_MISMATCH -> SecurityAdvisory.deployment("jenrepo.consistency.pointer", Severity.CRITICAL,
                    "Node " + divergence.nodeId() + " resolves a pointer differently",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". Two live nodes resolving the same "
                            + "pointer to different content is a split-brain resolution - a client gets different bytes "
                            + "depending on which node answers.",
                    "Investigate this node's derived-index snapshot and cache: force a re-sweep or restart it so it "
                            + "reconverges on the shared store's authoritative resolution.",
                    "", "", DOCS + "#jenrepo.consistency.pointer");
        };
    }
}
