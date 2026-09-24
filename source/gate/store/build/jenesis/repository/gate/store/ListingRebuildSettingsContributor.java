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
                        "Regenerate, at the end of a walk of the store, the stored listings - the packuments, Simple "
                                + "pages, Packages files, repodata, sparse-index files, tag lists and search documents "
                                + "a client fetches, each maintained incrementally by the write that changes it and "
                                + "materialised on first read - so any drift an interrupted write could have left is "
                                + "corrected by the walk (jenreg.walks) and never by a read. A listener of the one "
                                + "walk rather than a daily pass of its own; on by default.",
                        Setting.Kind.BOOLEAN, "true", true));
    }
}
