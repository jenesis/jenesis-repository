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
                                + "seconds, in UTC, and the consumers that ride it or every one installed, enabled "
                                + "unless it says otherwise. The entry named rebuild is the safety walk every consumer "
                                + "rides and a request runs; removing an entry stops that walk. Every walk reads every "
                                + "object in the store, which on an object store is a bill per pass, and without a "
                                + "crash no walk is necessary. Collection is the expensive consumer - reclaiming "
                                + "storage costs about 25 reads and 0.7 writes per object held, against about 33 reads "
                                + "and 1.4 writes for every other consumer of a whole walk together, and a write is "
                                + "priced at twelve to thirteen reads on the major object stores. Collecting daily "
                                + "rather than weekly returns reclaimed space within the day instead of within the "
                                + "week, and multiplies that part of the bill by seven; a store that does not charge "
                                + "per request pays neither way.",
                        Setting.Kind.STRING, WalkSchedules.DEFAULT, true).form(Setting.Form.JSON).advanced());
    }
}
