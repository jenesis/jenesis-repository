package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Puts the multi-node consistency dials in the deployment settings catalogue, beside the {@link NodeConsistency}
 * that reads them.
 *
 * <p>They govern how a fleet judges its own members - how fresh a fingerprint must be to count, how often the sweep
 * runs, how long a silent node has before it is called dead, and how long it is remembered after that. Declared
 * here, they are on the settings screen and in the generated reference, and the boot check for unrecognised settings
 * knows them, in every composition that carries the reader.
 *
 * <p>Two siblings are deliberately absent - {@code jenreg.consistency.node-id} is the node's own identity, taken
 * from its environment (a fleet sets it from the container hostname) and a caller must not be able to rename a node
 * out from under its lease, and {@code jenreg.consistency.enabled} decides whether the publisher is registered at
 * all, which the context settles as it starts. Both are named as {@link #startupKeys()} instead, so the boot check
 * for unrecognised settings knows them.
 */
public final class ConsistencySettingsContributor implements SettingsContributor {

    private static final String GROUP = "Consistency";

    /**
     * <strong>None of these is live, and they used to say they were.</strong> {@code Setting}'s {@code live} flag
     * means "applies on the next scheduled re-read, or only on the next restart", and the authoritative reader -
     * the {@code NodeConsistency} bean behind {@code /api/consistency} - resolves them once at boot through the
     * Spring environment. A stored edit reaches that reader through {@code SettingsEnvironmentLayer}, which exists
     * precisely for "values that cannot change live"; the live values go through {@code LiveConfig} instead, and
     * these do not.
     *
     * <p>What made the wrong flag worse than harmless is that one reader <em>is</em> per call:
     * {@code NodeDivergenceAdvisor} re-resolves them every time the posture screen renders. So an operator could
     * edit {@code dead-after}, watch the posture advisory change, conclude it had taken effect - and find
     * {@code /api/consistency}, which that class's own javadoc calls the authoritative read, still on the boot
     * value. Two surfaces disagreeing, with the one that updates being the one that does not decide anything.
     */

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("consistency.staleness-window", GROUP, "Fingerprint staleness window",
                        "How recently a node must have published its fingerprint to be counted live. A node quiet "
                                + "for longer is not yet dead - it is simply not compared, so a paused or restarting "
                                + "node does not read as divergent.",
                        Setting.Kind.DURATION, ConsistencyReport.Settings.DEFAULT_STALENESS_WINDOW, false),
                new Setting("consistency.sweep-interval", GROUP, "Consistency sweep interval",
                        "How often a node publishes its own fingerprint and compares the fleet's. Shorter detects "
                                + "divergence sooner and costs one small read and write per node per sweep.",
                        Setting.Kind.DURATION, ConsistencyReport.Settings.DEFAULT_SWEEP_INTERVAL, false),
                new Setting("consistency.sweep-intervals", GROUP, "Sweeps before a lagging node is stuck",
                        "How many sweep intervals a node may fail to advance its cursor before it is reported "
                                + "stuck rather than merely behind. A count of sweeps, not a duration: the budget "
                                + "is this many times the sweep interval.",
                        Setting.Kind.INTEGER, ConsistencyReport.Settings.DEFAULT_SWEEP_INTERVALS, false),
                new Setting("consistency.dead-after", GROUP, "Silence before a node is dead",
                        "How long a node may publish nothing before the fleet reports it dead rather than stale. "
                                + "Longer tolerates a slow restart; shorter surfaces a lost node sooner.",
                        Setting.Kind.DURATION, ConsistencyReport.Settings.DEFAULT_DEAD_AFTER, false),
                new Setting("consistency.forget-after", GROUP, "How long a dead node is remembered",
                        "How long a dead node's fingerprint is kept before the sweep removes it, so a "
                                + "decommissioned node leaves the report rather than sitting in it for good.",
                        Setting.Kind.DURATION, ConsistencyReport.Settings.DEFAULT_FORGET_AFTER, false),
                new Setting("consistency.heartbeat", GROUP, "Fingerprint publish interval",
                        "How often this node publishes its own fingerprint for the fleet to compare. Left unset it "
                                + "follows the sweep interval above, which is why it has no default of its own; set "
                                + "it only to publish more often than the fleet compares. Never shorter than one "
                                + "second, whatever is asked for.",
                        Setting.Kind.DURATION, "", false));
    }

    @Override
    public Set<String> startupKeys() {
        return Set.of("jenreg.consistency.enabled", "jenreg.consistency.node-id");
    }
}
