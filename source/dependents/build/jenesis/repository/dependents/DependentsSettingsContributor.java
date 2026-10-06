package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the reverse-dependency sweep's settings, so they surface exactly when this module is installed. The
 * cadence renders its key and default from {@link DependentsIndexTaskProvider#INTERVAL}.
 */
public final class DependentsSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("dependents-index", "Dependencies", "Reverse-dependency index",
                        "Build the reverse-dependency (\"who depends on X\") index on the background sweep: from "
                                + "the bill of materials an artifact embeds, and from the dependencies each manifest "
                                + "declares, which are listed apart with the requirement they state.",
                        Setting.Kind.BOOLEAN, "true", true).gate().standard(),
                new Setting(DependentsIndexTaskProvider.INTERVAL.key(), "Dependencies",
                        "Reverse-dependency sweep interval",
                        "How often the reverse-dependency index is rebuilt.",
                        Setting.Kind.DURATION, DependentsIndexTaskProvider.INTERVAL.fallbackText(), true).advanced(),
                new Setting("dependents-incremental", "Dependencies", "Incremental reverse-dependency index",
                        "Apply only the blobs that changed (from the dirty-index feed) each sweep instead of "
                                + "re-inverting every stored blob - the O(delta) steady state. Turn off to force a full "
                                + "rebuild every sweep (the safety valve); the periodic reconcile full-rebuilds either "
                                + "way.",
                        Setting.Kind.BOOLEAN, "true", false).advanced(),
                new Setting("dependents-reconcile-passes", "Dependencies", "Reverse-dependency reconcile cadence",
                        "How many incremental sweeps run between a full reconcile by the sweep itself; 0 leaves the "
                                + "reconcile to the walk: the dependents-rebuild consumer rebuilds from truth and "
                                + "compacts the feed when a walk carrying it runs (jenrepo.walks).",
                        Setting.Kind.INTEGER, "0", false).advanced(),
                new Setting(DependentsRebuildConsumer.NAME, "Dependencies", "Reverse-dependency rebuild on the walk",
                        "Rebuild the reverse-dependency index from every stored SBOM and compact its change feed at "
                                + "the end of a walk of the store that carries this consumer (jenrepo.walks): the "
                                + "reconcile that heals whatever the feed missed. Off leaves the index to the feed alone.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
