package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the write-ordering reconcile settings, so they surface on the settings screens exactly when this module is
 * installed. Both are deployment-wide (a listener of the walk, not a per-repository dial) and take effect on the next
 * walk, so flipping one needs no restart. The reconcile is on by default and a dry run by default - a removal of
 * pointer objects is opted into deliberately, never automatic.
 *
 * <p>The keys render straight off the {@link TornWriteConsumer#NAME} and {@link TornWriteConsumer#APPLY} constants,
 * so the catalogue and the consumer cannot drift.
 */
public final class TornWriteReconcileSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(TornWriteConsumer.NAME, "Maintenance", "Reconcile torn writes",
                        "Judge crash-torn intermediate states whenever a walk of the store runs - a pointer whose "
                                + "blob is missing (flagged loudly; impossible under the blob-before-pointer ordering, "
                                + "so a signal of corruption) and a blob no pointer references (an orphan, confirmed "
                                + "and left to garbage collection). A listener of the one walk (jenrepo.walks) rather "
                                + "than a sweep of its own, paying no read the walk did not already make; on by "
                                + "default, and a dry run that flags and counts unless Apply is also set.",
                        Setting.Kind.BOOLEAN, "true", true).advanced(),
                new Setting(TornWriteConsumer.APPLY, "Maintenance", "Apply torn-write repairs",
                        "When the torn-write reconcile is on, actually remove the dangling pointers a walk finds (a "
                                + "pointer that serves nothing because its blob is gone) rather than only flagging and "
                                + "counting them. Orphan blobs are always left to garbage collection; a referenced "
                                + "object is never removed. Off by default, so removal is a deliberate opt-in.",
                        Setting.Kind.BOOLEAN, "false", true).advanced());
    }
}
