package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the published-index settings, so they appear on the settings screens exactly when this module is installed.
 *
 * <p>The cadence entries render their key, default and (for the rebase) maximum from
 * {@link PublishedIndexTaskProvider}'s {@code IntervalSetting} constants. The rebase's maximum is stated in its
 * description so an operator learns of the cap where they configure it rather than from a log.
 */
public final class IndexSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("index", "Index", "Published index",
                        "Publish an incremental, resumable repository index (Zstandard seekable chunks + descriptor) "
                                + "on the background sweep.",
                        Setting.Kind.BOOLEAN, "false", false).standard(),
                new Setting(PublishedIndexTaskProvider.INTERVAL.key(), "Index", "Index interval",
                        "How often an incremental index chunk is published.",
                        Setting.Kind.DURATION, PublishedIndexTaskProvider.INTERVAL.fallbackText(), false).advanced(),
                new Setting("index-max-chunk", "Index", "Index chunk size",
                        "Maximum compressed size in bytes of one published index chunk before it rotates.",
                        Setting.Kind.LONG, "8388608", false).advanced(),
                new Setting(IndexRebaseConsumer.NAME, "Index", "Index rebase on the walk",
                        "Rebase the published index onto a fresh chunk chain from every served pointer at the end "
                                + "of a walk of the store that carries this consumer (jenrepo.walks). Published chunks "
                                + "are immutable and cached by consumers, so the rebase is what removes a "
                                + "retroactively withheld path from the index once the live retraction signal was "
                                + "missed; carry it on a scheduled walk.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
