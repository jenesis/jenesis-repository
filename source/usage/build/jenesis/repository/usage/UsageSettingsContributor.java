package build.jenesis.repository.usage;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the usage tracker's setting, so it surfaces on the settings screens exactly when an image carries the
 * tracker.
 */
public final class UsageSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("track-key-usage", "Operations", "Track key usage",
                        "Stamp each credential's last use, at most once per day.",
                        Setting.Kind.BOOLEAN, "true", false).gate());
    }
}
