package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Surfaces the raw format's one dial: which of a proxying repository's files move ({@value RawFormat#MOVING}). */
public final class RawSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(RawFormat.MOVING, "Raw", "Files that move",
                "The files of a raw repository proxying an upstream that the upstream rewrites in place, as globs "
                        + "under its root separated by commas - '**/latest.json' for the document naming Grype's "
                        + "current database. Such a file is relayed as the upstream serves it now, remembered for "
                        + "the upstream document ttl, and never kept; every other file is fetched once and served "
                        + "from its copy ever after. Empty for none.",
                Setting.Kind.STRING, "", true, Setting.Scope.REPOSITORY).advanced());
    }
}
