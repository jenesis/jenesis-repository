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
                        "Lift, whenever a walk of the store runs, a content-addressed withheld/<hash> serving marker "
                                + "for which no live holder remains - a marker stranded by two byte-identical aliases "
                                + "releasing at once, by a crash in the enforce sweep's marker-before-pointer window, "
                                + "or a pre-existing orphan. A marker is lifted only when a live published coordinate "
                                + "still claims the bytes AND no /quarantine review pointer or holds/ record covers "
                                + "them, so a rejected (marker-only) manifest stays withheld. A listener of the one "
                                + "walk (jenrepo.walks) rather than a sweep of its own.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
