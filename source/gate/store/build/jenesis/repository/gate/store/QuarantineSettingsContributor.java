package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the quarantine log's retention dials, so they render on the settings screens, {@code /api/settings} and
 * the CLI exactly when this module is installed, and apply live (the next retention pass reads the current values).
 */
public final class QuarantineSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(QuarantineRetentionTask.RETENTION.key(), "Retention", "Quarantine log retention",
                        "Remove gate-decision log rows older than this ISO-8601 duration on the scheduled cleanup "
                                + "pass; a still-held path keeps its verdict whatever its age. PT0S disables age "
                                + "pruning.",
                        Setting.Kind.DURATION, QuarantineRetentionTask.RETENTION.fallbackText(), true),
                new Setting("quarantine-log-cap", "Retention", "Quarantine log cap",
                        "Keep at most this many newest gate-decision log rows; 0 disables the count cap.",
                        Setting.Kind.INTEGER, "0", true),
                new Setting("strict-hold-mapping", "Compliance", "Strict hold-mapping",
                        "Off by default: after an accepted publish through a blobs-namespace format, the publish-time "
                                + "hold-mapping round-trip check verifies the format's blobKeys/servedPaths resolve the "
                                + "served path and content hash just laid out (so a hold placed after the publish could "
                                + "retract it). A broken mapping always alarms (jenreg.publish.holdmapping.broken). "
                                + "Turn it on to also FAIL such a publish rather than only alarm - on in every test "
                                + "config so a wiring regression fails on the first publish; off in production so one "
                                + "broken format cannot DoS publishes.",
                        Setting.Kind.BOOLEAN, "false", true));
    }
}
