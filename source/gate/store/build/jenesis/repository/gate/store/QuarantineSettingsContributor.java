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
                new Setting(QuarantineRetentionTask.RETENTION.key(), "Record lifetimes", "Quarantine log retention",
                        "Remove gate-decision log rows older than this on the scheduled cleanup pass; a still-held "
                                + "path keeps its verdict whatever its age. Zero switches age pruning off.",
                        Setting.Kind.DURATION, QuarantineRetentionTask.RETENTION.fallbackText(), true).advanced(),
                new Setting("quarantine-log-cap", "Record lifetimes", "Quarantine log cap",
                        "Keep at most this many newest gate-decision log rows; zero sets no count cap.",
                        Setting.Kind.INTEGER, "0", true).advanced(),
                new Setting("strict-hold-mapping", "Compliance", "Strict hold-mapping",
                        "After an accepted publish through a blobs-namespace format, the publish-time hold-mapping "
                                + "round-trip check verifies the format's blobKeys/servedPaths resolve the served "
                                + "path and content hash just laid out (so a hold placed after the publish could "
                                + "retract it). A broken mapping always alarms (jenrepo.publish.holdmapping.broken); "
                                + "on, it also FAILS such a publish rather than only alarming. Test configurations "
                                + "turn it on so a wiring regression fails on the first publish; production leaves it "
                                + "off so one broken format cannot DoS publishes.",
                        Setting.Kind.BOOLEAN, "false", true).advanced());
    }
}
