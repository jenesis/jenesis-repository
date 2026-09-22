package build.jenesis.repository.downloads;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the download tracker's setting, so it surfaces on the settings screens exactly when this module is
 * installed.
 */
public final class DownloadSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("track-downloads", "Operations", "Track downloads",
                        "Run the download-tracking worker; needed for the not-downloaded-for criterion.",
                        Setting.Kind.BOOLEAN, "true", false).gate(),
                new Setting("download-flush-interval", "Operations", "Download flush interval",
                        "How long download hits are held in memory before one compare-and-set adds them to the "
                                + "version's document and refreshes its last-download instant - at most one write "
                                + "per coordinate version per interval, and a count that lags by at most that. 0 or "
                                + "off writes on every drain. Applies on restart.",
                        Setting.Kind.DURATION, "PT6H", false));
    }
}
