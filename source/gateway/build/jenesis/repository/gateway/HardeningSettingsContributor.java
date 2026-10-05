package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the hardening proxy's runtime dials so they render on the settings screens, {@code /api/settings} and the
 * CLI exactly when the gateway module is installed, and apply live (the next pass reads the current values). It is
 * the late-enablement migration re-screen sweep ({@link MigrationRescreenTaskProvider}): a switch that turns the
 * back-fill on and the cadence that paces it - the cadence of the pending re-screen ({@link PendingScreenTaskProvider})
 * - and the repository mark that a repository's upstreams are internal
 * ({@link GatePolicyProvider.Path#UPSTREAM_INTERNAL}), which decides the gate flavour its fetches are screened through.
 * The sweep's untrusted-upstream fetch bounds and the spool budget are
 * deploy-time resource dials bound from the environment (the {@code spool.*} keys), not UI settings, so they are not
 * declared here.
 */
public final class HardeningSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("harden-rescreen", "Hardening proxy", "Migration re-screen sweep",
                        "Back-fill a repository switched to `harden` late: a Lease-guarded, idempotent background pass "
                                + "re-screens the artifacts cached before hardening was enabled from their local bytes, "
                                + "records the digest-pinned verdict, and evicts any that re-screen non-ALLOW so a "
                                + "subsequent request re-fetches through the hardened leg. A converged pass re-screens "
                                + "nothing.",
                        Setting.Kind.BOOLEAN, "false", true).gate().advanced(),
                new Setting(MigrationRescreenTaskProvider.INTERVAL.key(), "Hardening proxy",
                        "Migration re-screen interval",
                        "How often the migration re-screen sweep runs. Every pass lists every cached artifact of every "
                                + "hardened repository, and a late-flipped repository is verified fail-closed on every "
                                + "read until the sweep reaches it.",
                        Setting.Kind.DURATION, MigrationRescreenTaskProvider.INTERVAL.fallbackText(), true).advanced(),
                new Setting(PendingScreenTaskProvider.INTERVAL.key(), "Compliance", "Pending re-screen interval",
                        "How often a copy served while an advisory feed could not answer is screened again. Only a "
                                + "repository whose screening mode admits through an outage has such copies, and a "
                                + "pass over one that has none is a single listing; the interval is how long a "
                                + "recovered feed's answer waits to hold what it flags.",
                        Setting.Kind.DURATION, PendingScreenTaskProvider.INTERVAL.fallbackText(), true).advanced(),
                new Setting(GatePolicyProvider.Path.UPSTREAM_INTERNAL, "Proxy", "Internal upstream",
                        "Treat what this repository fetches from its upstreams as the organisation's own: another "
                                + "instance it publishes to, reached over HTTP. A copy it fetches is judged as a "
                                + "version published here is - asked of no advisory feed, not checked against the "
                                + "private names, held to the publishing licence and signature rules. Leave it off "
                                + "for a public registry: an upstream reached over HTTP is public unless marked.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.REPOSITORY).advanced());
    }
}
