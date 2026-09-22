package build.jenesis.repository.walk.task;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the rebuild-pass settings, so they surface on the settings screens exactly when this module is
 * installed. Both are deployment-wide (a background sweep, not a per-repository dial) and take effect on the next
 * task re-resolve, so flipping one needs no restart.
 *
 * <p>The cadence entry renders its key and default straight off {@link RebuildTaskProvider}'s
 * {@link WalkSchedules#DEFAULT} constant, so the catalogue and the code cannot drift.
 */
public final class WalkSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("rebuild", "Maintenance", "Walk rebuild pass",
                        "Drive every discovered walk consumer (a derived-metadata rebuilder's back-fill, refresh "
                                + "and self-heal route) from one shared enumeration of the pointer roots. On by "
                                + "default: without a consumer nothing is enumerated, and without an installed walk "
                                + "implementation no pass schedules at all.",
                        Setting.Kind.BOOLEAN, "true", true).gate(),
                new Setting(WalkSchedules.SETTING, "Maintenance", "Walks",
                        "The walks of the store this deployment schedules, as a JSON array of entries - each a name, "
                                + "a cron expression (Spring's grammar with seconds, in UTC) and the consumers that "
                                + "ride it (\"*\" for every one installed), enabled unless said otherwise. Every "
                                + "walk reads every object in the store, which on an object store is a bill per "
                                + "pass: the entry named rebuild is the safety walk every consumer rides and a "
                                + "request runs, weekly by default; retention's daily walk runs by itself and is "
                                + "removed to opt out. Without a crash, no walk is necessary. Collection is the "
                                + "expensive consumer - reclaiming storage costs about 25 reads and 0.7 writes "
                                + "per object held, against about 33 reads and 1.4 writes for every other "
                                + "consumer of a whole walk together, and a write is priced at twelve to "
                                + "thirteen reads on the major object stores. It rides the weekly walk for that "
                                + "reason. Adding collect to the daily entry returns reclaimed space within the "
                                + "day instead of within the week, and multiplies that part of the bill by "
                                + "seven; a store that does not charge per request pays neither way.",
                        Setting.Kind.STRING, WalkSchedules.DEFAULT, true));
    }
}
