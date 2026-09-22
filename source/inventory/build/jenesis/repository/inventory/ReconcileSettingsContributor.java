package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the reconcile setting, so it surfaces on the settings screens exactly when this module is installed:
 * deployment-wide (a listener of the walk, not a per-repository dial) and read on the next walk, so flipping it
 * needs no restart. The key renders straight off {@link InventoryReconcileConsumer#NAME}, so the catalogue and the
 * consumer cannot drift.
 */
public final class ReconcileSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(InventoryReconcileConsumer.NAME, "Maintenance", "Reconcile derived state",
                        "Rebuild the publish-time inventory facts from the live pointer tree, in both directions, "
                                + "whenever a walk of the store runs: a crash that skipped a sidecar write converges "
                                + "instead of leaving a served artifact invisible to retention and the search and "
                                + "license index, and a crashed eviction's orphan facts and derived rows go. A listener "
                                + "of the one walk (jenreg.walks) rather than a sweep of its own; on by default, and "
                                + "nothing until a walk runs.",
                        Setting.Kind.BOOLEAN, "true", true));
    }
}
