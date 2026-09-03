package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The observability face of the multi-node consistency check: the fleet's per-node numbers reported as
 * self-describing signals, so the same overview that shows every other {@code jenreg.*} signal shows how many nodes are
 * live and whether any is diverged. It reports {@code jenreg.consistency.nodes} (a gauge of live nodes),
 * {@code jenreg.consistency.diverged} (a gauge of stuck divergences), and a {@code jenreg.consistency.divergence}
 * health check that is {@link Health#UP} when the fleet has converged (or is single-node) and {@link Health#DEGRADED}
 * when a node is stuck - a detect-not-block signal, never a failure.
 *
 * <p>These numbers are the very thing that makes the "these numbers are instance-specific; warn when multiple
 * nodes" caveat trustworthy: the overview can now say <em>how many</em> instances there are and whether they agree. A
 * single-node deployment reports one node and full health - no false divergence. Reading it is cheap: it runs the same
 * bounded {@link NodeConsistency#report} (the node prefix plus one small object per node), never a scan; a store it
 * cannot read reports nothing rather than failing the overview.
 */
public final class NodeConsistencyObservability implements ObservabilitySource {

    private static final AtomicReference<NodeConsistency> INSTALLED = new AtomicReference<>();

    private final NodeConsistency check;

    public NodeConsistencyObservability(NodeConsistency check) {
        this.check = Objects.requireNonNull(check, "check");
    }

    /** The discovered, no-argument form the report loads through {@code ServiceLoader}: reports from the
     *  {@linkplain #install installed} check, and nothing before one is installed. */
    public NodeConsistencyObservability() {
        this.check = null;
    }

    /** Register the live consistency check the discovered form reports from; the last registration wins. */
    public static void install(NodeConsistency check) {
        INSTALLED.set(Objects.requireNonNull(check, "check"));
    }

    @Override
    public List<Metric> metrics() {
        ConsistencyReport report = report();
        if (report == null) {
            return List.of();
        }
        return List.of(
                Metric.gauge("jenreg.consistency.nodes",
                        "Live nodes sharing this store (heartbeating within the staleness window).",
                        report.liveCount(), "nodes"),
                Metric.gauge("jenreg.consistency.diverged",
                        "Nodes flagged stuck-diverged from the fleet (config, cursor or pointer split).",
                        report.divergences().size(), "nodes"));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        ConsistencyReport report = report();
        if (report == null) {
            return List.of();
        }
        if (report.singleNode()) {
            return List.of(new HealthCheck("jenreg.consistency.divergence",
                    "Whether any node has diverged from the fleet (detect-only, never blocks).", Health.UP,
                    "single node - nothing to diverge from"));
        }
        Health health = report.converged() ? Health.UP : Health.DEGRADED;
        String detail = report.converged()
                ? report.liveCount() + " live nodes converged"
                : report.divergences().size() + " of " + report.liveCount() + " live nodes diverged";
        return List.of(new HealthCheck("jenreg.consistency.divergence",
                "Whether any node has diverged from the fleet (detect-only, never blocks).", health, detail));
    }

    /** The current report, or {@code null} when the store cannot be read - the graceful "report nothing" path that
     *  keeps the overview from failing on a transient store error. */
    private ConsistencyReport report() {
        NodeConsistency live = check != null ? check : INSTALLED.get();
        if (live == null) {
            return null;                                        // nothing installed: no signal, not a healthy one
        }
        try {
            return live.report(System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
