package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the hardening proxy's runtime dials so they render on the settings screens, {@code /api/settings} and the
 * CLI exactly when the gateway module is installed, and apply live (the next pass reads the current values). For now
 * this is the late-enablement migration re-screen sweep ({@link MigrationRescreenTaskProvider}): a switch that turns the
 * back-fill on and the cadence that paces it. The sweep's untrusted-upstream fetch bounds and the spool budget are
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
                        Setting.Kind.BOOLEAN, "false", true).gate(),
                new Setting(MigrationRescreenTaskProvider.INTERVAL.key(), "Hardening proxy",
                        "Migration re-screen interval",
                        "How often the migration re-screen sweep runs, as an ISO-8601 duration. Daily by default: "
                                + "every pass lists every cached artifact of every hardened repository, and a "
                                + "late-flipped repository is verified fail-closed on every read until the sweep "
                                + "reaches it.",
                        Setting.Kind.DURATION, MigrationRescreenTaskProvider.INTERVAL.fallbackText(), true));
    }
}
