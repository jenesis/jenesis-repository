package build.jenesis.repository.gc.walk;

import module java.base;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the collector's settings - whether a walk reclaims, which collector, its stride and its grace - so they
 * surface on the settings screens and in the generated reference exactly when an image carries the collection pass.
 */
public final class CollectionSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("collect", "Collection", "Reclaim unreferenced content",
                        "Run the collector at the end of a walk, so the storage of content no live pointer names any "
                                + "more is freed. A blob is condemned on one pass and deleted on the next, and a "
                                + "pointer linking it in between clears the mark, so nothing is deleted that was "
                                + "referenced within two whole passes. Arming the collector is not scheduling it: it "
                                + "reclaims only on the walks that name it in the walks setting, and a walk's cadence "
                                + "is a cost dial - a pass over the whole store weekly costs a seventh of one that "
                                + "runs daily.",
                        Setting.Kind.BOOLEAN, "true", true).gate().standard(),
                new Setting("gc", "Collection", "Collector",
                        "The collector to use, by name. A name nothing answers to fails the boot rather than "
                                + "quietly reclaiming nothing.",
                        Setting.Kind.STRING, "mark-sweep", false).advanced(),
                new Setting("gc.stride", "Collection", "Collector stride",
                        "Items the collector handles between checkpoints: the reference batch it holds in memory, "
                                + "the re-work a crash costs, and how often it renews a segment claim. Keep the "
                                + "stride times the per-item time well under the walk's claim time-to-live.",
                        Setting.Kind.INTEGER, "20000", true).advanced(),
                new Setting("gc.grace", "Collection", "Collector grace",
                        "A wall-clock floor on the gap between condemning a blob and deleting it, on top of the "
                                + "two-pass rule, so an upload whose pieces are unreferenced for a while - a push's "
                                + "layers before its manifest - is not collected when collection runs often. It only "
                                + "ever delays a deletion; zero leaves the two-pass rule alone.",
                        Setting.Kind.DURATION, GarbageCollector.DEFAULT_GRACE, true).advanced());
    }
}
