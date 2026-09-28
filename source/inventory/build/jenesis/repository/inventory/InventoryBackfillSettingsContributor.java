package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the inventory back-fill's one setting: the {@code Features} toggle of the
 * {@link InventoryBackfillConsumer} walk consumer, deployment-wide and effective on the next rebuild pass, so
 * flipping it needs no restart.
 *
 * <p>It carries no cadence dial. The pass runs on the walks setting ({@code jenrepo.walks}) for every consumer at
 * once, and a second schedule here would be one this consumer does not own.
 */
public final class InventoryBackfillSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting("inventory-backfill", "Maintenance", "Rebuild missing inventory rows",
                "Let the shared rebuild pass restore the inventory row of a blobs-namespace version whose row "
                        + "is missing, reading the coordinate back out of its own stored pointer - as a cached copy "
                        + "where its origin shows it was fetched from an upstream, as a release otherwise. A row "
                        + "goes missing when a publish is interrupted after the artifact is committed and before "
                        + "its row is written, and a cached copy's when its fill's notice is lost: the "
                        + "artifact keeps serving, but a retroactive advisory sweep no longer enumerates it, so it "
                        + "can carry a later-listed CVE while the held count reads clean. It costs one membership "
                        + "probe per live pointer once converged.",
                Setting.Kind.BOOLEAN, "true", false).advanced());
    }
}
