package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the withhold-reconcile setting, so it surfaces on the settings screens exactly when this module is
 * installed. It is deployment-wide (a background sweep, not a per-repository dial) and takes effect on the next
 * pass, so flipping it needs no restart. Its key is the {@link WithheldReconcileConsumer#NAME} constant, so the
 * catalogue and the consumer cannot drift.
 */
public final class WithheldReconcileSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(WithheldReconcileConsumer.NAME, "Maintenance", "Reconcile withhold markers",
                        "Lift, whenever a walk of the store runs, a withheld serving marker for which no live holder "
                                + "remains - one stranded by two byte-identical aliases releasing at once, or by a "
                                + "crash. A marker is lifted only when a published coordinate still claims the bytes "
                                + "and no review pointer or hold covers them, so a rejected manifest stays withheld.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
