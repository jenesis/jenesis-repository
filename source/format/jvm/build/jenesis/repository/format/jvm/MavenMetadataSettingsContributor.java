package build.jenesis.repository.format.jvm;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the Maven format's metadata-computation opt-in. Off by default: a {@code maven-metadata.xml} is stored and
 * served verbatim. Switched on, an artifact-level document's {@code <versions>} list is reconciled against the stored
 * folders (every other field kept as the publisher wrote it) and a document is derived for a coordinate that never had
 * one uploaded, as after an import. The format reads the value from the {@code jenrepo.} environment, into which a
 * stored setting is layered at boot, so it is restart-bound ({@code live=false}). The key is defined here and the format
 * reads it from here.
 */
public final class MavenMetadataSettingsContributor implements SettingsContributor {

    /** The setting key (under {@code jenrepo.}) that opts a deployment into computing {@code maven-metadata.xml}. */
    public static final String COMPUTE_SETTING = "maven-metadata-compute";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(COMPUTE_SETTING, "Maven", "Compute maven-metadata.xml",
                        "Compute the artifact-level maven-metadata.xml on read rather than serving the publisher's "
                                + "stored document verbatim: reconcile only its <versions> list against the stored "
                                + "version folders (every other field preserved), and derive a document for a "
                                + "coordinate no client ever uploaded one for (an imported or batch-ingested "
                                + "repository). In a repository that also proxies, the document lists the "
                                + "upstream's versions beside the ones published here. Applies on the next restart.",
                        Setting.Kind.BOOLEAN, "false", false).advanced());
    }
}
