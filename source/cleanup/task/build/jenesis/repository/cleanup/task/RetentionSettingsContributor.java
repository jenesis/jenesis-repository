package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the retention settings, so they surface on the settings screens exactly when this module is installed -
 * without the engine, a retention dial would tune nothing.
 *
 * <p>The cadence entry renders its key and default straight off {@link CleanupTaskProvider}'s {@code IntervalSetting}
 * constant, and the import-job ttl off {@link CleanupTask}'s {@code RetentionSetting}, so the catalogue and the code
 * cannot drift. The cadence is the whole retention family's dial - four more reapers read the same key from their
 * own modules - each stating the same default itself, since a constant cannot be shared across module boundaries.
 */
public final class RetentionSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("retention", "Retention", "Retention engine",
                        "Select the retention engine by name; empty resolves the single enabled engine, and more "
                                + "than one enabled engine needs this setting to disambiguate them. A named "
                                + "selection that no installed engine answers to fails fast rather than silently "
                                + "degrading to no retention.",
                        Setting.Kind.STRING, "", true),
                new Setting("keep-last", "Retention", "Keep last",
                        "Keep at most this many newest versions per coordinate; 0 disables the count cap.",
                        Setting.Kind.INTEGER, "0", true),
                new Setting("max-age", "Retention", "Maximum age",
                        "Evict versions older than this ISO-8601 duration; empty disables age eviction.",
                        Setting.Kind.DURATION, "", true),
                new Setting("prerelease-expiry", "Retention", "Prerelease expiry",
                        "Evict prereleases older than this ISO-8601 duration; empty disables.",
                        Setting.Kind.DURATION, "", true),
                new Setting("not-downloaded-for", "Retention", "Not downloaded for",
                        "Evict versions not downloaded within this ISO-8601 duration; needs download tracking.",
                        Setting.Kind.DURATION, "", true),
                new Setting("scheduled-cleanup", "Retention", "Scheduled cleanup",
                        "Run the scheduled reaps: finished import jobs past their time-to-live and a quota'd "
                                + "tenant's usage recount. Retention, garbage collection and the folder-size "
                                + "roll-up ride the walks setting's retention entry instead.",
                        Setting.Kind.BOOLEAN, "true", true).gate(),
                new Setting(CleanupTaskProvider.INTERVAL.key(), "Retention", "Cleanup interval",
                        "How often the scheduled reaps run.",
                        Setting.Kind.DURATION, CleanupTaskProvider.INTERVAL.fallbackText(), true),
                new Setting(CleanupTask.IMPORT_JOB_TTL.key(), "Retention", "Import job time-to-live",
                        "Auto-dismiss completed or failed migration jobs (and their remembered sources) this "
                                + "ISO-8601 duration after the sweep first sees them finished; a running job is "
                                + "never touched. PT0S disables the auto-dismiss.",
                        Setting.Kind.DURATION, CleanupTask.IMPORT_JOB_TTL.fallbackText(), true),
                new Setting(CleanupTask.EXPORT_JOB_TTL.key(), "Retention", "Export job time-to-live",
                        "How long a finished export job's status stays before the scheduled cleanup dismisses it. "
                                + "Zero, negative or blank keeps every job until an operator dismisses it by hand.",
                        Setting.Kind.DURATION, CleanupTask.EXPORT_JOB_TTL.fallbackText(), true));
    }
}
