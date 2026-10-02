package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the retention settings, so they appear exactly when this module is installed. The cadence entry renders
 * {@link CleanupTaskProvider}'s {@code IntervalSetting} and the job TTLs {@link CleanupTask}'s
 * {@code RetentionSetting}s, so the catalogue cannot drift. The cadence is the retention family's dial: other reapers
 * read the same key from their own modules, each stating the same default, since a constant cannot be shared across
 * those module boundaries.
 */
public final class RetentionSettingsContributor implements SettingsContributor {

    /** A value the kind accepts but a policy does not - a negative count, or a zero or negative age, which would evict
     *  all but each coordinate's newest version. The policy's own parse decides, so this refuses exactly what a sweep
     *  would. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        try {
            switch (setting.key()) {
                case RetentionPolicy.KEEP_LAST -> RetentionPolicy.parse(value, null, null, null);
                case RetentionPolicy.MAX_AGE -> RetentionPolicy.parse(null, value, null, null);
                case RetentionPolicy.PRERELEASE_EXPIRY -> RetentionPolicy.parse(null, null, value, null);
                case RetentionPolicy.NOT_DOWNLOADED_FOR -> RetentionPolicy.parse(null, null, null, value);
                default -> {
                }
            }
            return Optional.empty();
        } catch (IllegalArgumentException refused) {
            return Optional.of(refused.getMessage());
        }
    }

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("retention", "Retention", "Retention engine",
                        "Select the retention engine by name; empty resolves the single enabled engine, and more "
                                + "than one enabled engine needs this setting to disambiguate them. A named "
                                + "selection that no installed engine answers to fails fast rather than silently "
                                + "degrading to no retention.",
                        Setting.Kind.STRING, "", true).advanced(),
                new Setting(RetentionPolicy.KEEP_LAST, "Retention", "Keep last",
                        "Keep at most this many newest versions per coordinate; 0 disables the count cap.",
                        Setting.Kind.INTEGER, "0", true, Setting.Scope.REPOSITORY).essential(),
                new Setting(RetentionPolicy.MAX_AGE, "Retention", "Maximum age",
                        "Evict versions older than this duration (P30D, 30d); none switches the rule off.",
                        Setting.Kind.DURATION_OR_NONE, "", true, Setting.Scope.REPOSITORY).essential(),
                new Setting(RetentionPolicy.PRERELEASE_EXPIRY, "Retention", "Prerelease expiry",
                        "Evict prereleases older than this duration; none switches the rule off.",
                        Setting.Kind.DURATION_OR_NONE, "", true, Setting.Scope.REPOSITORY).essential(),
                new Setting(RetentionPolicy.NOT_DOWNLOADED_FOR, "Retention", "Not downloaded for",
                        "Evict versions not downloaded within this duration - it needs download tracking; none "
                                + "switches the rule off.",
                        Setting.Kind.DURATION_OR_NONE, "", true, Setting.Scope.REPOSITORY).essential(),
                new Setting("scheduled-cleanup", "Retention", "Scheduled cleanup",
                        "Run the scheduled reaps: finished import jobs past their time-to-live and the usage recount "
                                + "the storage quota is held against. Retention, garbage collection and the "
                                + "folder-size roll-up ride the walks setting's retention entry instead.",
                        Setting.Kind.BOOLEAN, "true", true).gate().advanced(),
                new Setting(CleanupTaskProvider.INTERVAL.key(), "Retention", "Cleanup interval",
                        "How often the scheduled reaps run.",
                        Setting.Kind.DURATION, CleanupTaskProvider.INTERVAL.fallbackText(), true).advanced(),
                new Setting(CleanupTask.IMPORT_JOB_TTL.key(), "Record lifetimes", "Import job time-to-live",
                        "Auto-dismiss completed or failed migration jobs (and their remembered sources) this "
                                + "ISO-8601 duration after the sweep first sees them finished; a running job is "
                                + "never touched. PT0S disables the auto-dismiss.",
                        Setting.Kind.DURATION, CleanupTask.IMPORT_JOB_TTL.fallbackText(), true).advanced(),
                new Setting(CleanupTask.EXPORT_JOB_TTL.key(), "Record lifetimes", "Export job time-to-live",
                        "How long a finished export job's status stays before the scheduled cleanup dismisses it. "
                                + "Zero, negative or blank keeps every job until an operator dismisses it by hand.",
                        Setting.Kind.DURATION, CleanupTask.EXPORT_JOB_TTL.fallbackText(), true).advanced());
    }
}
