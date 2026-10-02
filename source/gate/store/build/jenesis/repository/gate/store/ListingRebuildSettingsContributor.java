package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** The setting of the {@link ListingRebuildConsumer}: its switch, on by default, since it runs only when a walk carrying it does. */
public final class ListingRebuildSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(ListingRebuildConsumer.NAME, "Maintenance", "Rebuild stored listings",
                        "Regenerate, at the end of a walk of the store, the stored listings a client fetches - "
                                + "packuments, Simple pages, Packages files, repodata, sparse-index files, tag lists "
                                + "and search documents - so any drift an interrupted write left is corrected by the "
                                + "walk and never by a read.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
