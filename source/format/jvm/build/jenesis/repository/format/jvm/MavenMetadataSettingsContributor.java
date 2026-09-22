package build.jenesis.repository.format.jvm;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the Maven format's metadata-computation opt-in, so the dial appears exactly when an image ships the free
 * Maven layout. Off by default: a {@code maven-metadata.xml} is stored and served verbatim, the
 * full wire-fidelity behaviour. Switching it on has an artifact-level document's {@code <versions>} list reconciled
 * against the stored folders (every other field kept as the publisher wrote it) and a document derived for a
 * coordinate that never had one uploaded - the importer / batch case. The free Maven format reads the value off the
 * exchange from the {@code jenreg.} environment, into which a stored setting is layered at boot, so it is
 * restart-bound ({@code live=false}) and this module describes it without the free format depending on the settings
 * layer.
 *
 * <p>The key is named as the wire string the two sides already agree on (the {@code ratelimit/bundle} sibling does the
 * same for {@code rate-limit}) rather than read off {@code MavenMetadata.COMPUTE_SETTING}: a describing module must not
 * take a compile-time edge to a format implementation just to spell a settings key, or every touch of that format
 * rebuilds every image. The two spellings are held together where the coupling belongs - a test - by
 * {@code MavenMetadataSettingsContributorTest}, which does require the free Maven format and fails the build if the
 * constant and this literal ever drift apart.
 */
public final class MavenMetadataSettingsContributor implements SettingsContributor {

    /** The free Maven format's {@code MavenMetadata.COMPUTE_SETTING}, pinned to that constant by the bundle test. */
    private static final String COMPUTE_SETTING = "maven-metadata-compute";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(COMPUTE_SETTING, "Maven", "Compute maven-metadata.xml",
                        "Compute the artifact-level maven-metadata.xml on read rather than serving the publisher's "
                                + "stored document verbatim: reconcile only its <versions> list against the stored "
                                + "version folders (every other field preserved), and derive a document for a "
                                + "coordinate no client ever uploaded one for (an imported or batch-ingested "
                                + "repository). Off by default; applies on the next restart.",
                        Setting.Kind.BOOLEAN, "false", false));
    }
}
