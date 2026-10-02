package build.jenesis.repository.walk.task;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the walk settings - the {@code rebuild} gate and {@link WalkSchedules#SETTING} - so they appear exactly
 * when this module is installed. Both are deployment-wide and apply on the next task re-resolve, without a restart. The
 * schedule's default renders {@link WalkSchedules#DEFAULT}, so the catalogue cannot drift.
 */
public final class WalkSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("rebuild", "Maintenance", "Walk rebuild pass",
                        "Drive every discovered walk consumer (a derived-metadata rebuilder's back-fill, refresh and "
                                + "self-heal route) from one shared enumeration of the pointer roots. Without a "
                                + "consumer nothing is enumerated, and without an installed walk implementation no "
                                + "pass schedules at all.",
                        Setting.Kind.BOOLEAN, "true", true).gate().advanced(),
                new Setting(WalkSchedules.SETTING, "Maintenance", "Walks",
                        "The walks of the store this deployment schedules, each with a name, a cron schedule with "
                                + "seconds, in UTC, and the consumers that ride it. The entry named rebuild is the "
                                + "safety walk every consumer rides; removing an entry stops that walk. Every walk "
                                + "reads every object, so on an object store its cadence is a cost dial. Collection is "
                                + "the expensive consumer: collecting daily rather than weekly frees space sooner at "
                                + "seven times that cost.",
                        Setting.Kind.STRING, WalkSchedules.DEFAULT, true).form(Setting.Form.JSON).advanced());
    }
}
