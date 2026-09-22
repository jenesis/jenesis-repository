package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the staging reap's TTL, so it renders on the settings screens, {@code /api/settings} and the CLI exactly
 * when this module is installed, and applies live (the next reap pass reads the current value).
 */
public final class StagingSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(StagingReapTask.TTL.key(), "Retention", "Staging time-to-live",
                "On the scheduled cleanup pass, drop open staging repositories untouched for this ISO-8601 duration "
                        + "(their staged artifacts are unpublished and garbage-collected) and remove "
                        + "promoted/dropped staging markers of the same age. PT0S disables the reap.",
                Setting.Kind.DURATION, StagingReapTask.TTL.fallbackText(), true));
    }
}
