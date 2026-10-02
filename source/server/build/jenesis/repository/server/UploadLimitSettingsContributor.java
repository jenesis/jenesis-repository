package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the bound on one request's body, read live by the {@link UploadLimitFilter} that applies it. */
public final class UploadLimitSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(UploadLimitFilter.KEY, "Uploads", "Largest upload",
                        "The most one request may send, in bytes: a publish declaring a larger body is refused with "
                                + "413 before any of it is read, and one streaming without a declared length is "
                                + "refused at the byte that crosses it, so nothing of it is kept. Per request - for a "
                                + "registry that uploads in chunks, per chunk. Zero lifts the bound. Applies live.",
                        Setting.Kind.LONG, UploadLimitFilter.DEFAULT_TEXT, true).advanced());
    }
}
