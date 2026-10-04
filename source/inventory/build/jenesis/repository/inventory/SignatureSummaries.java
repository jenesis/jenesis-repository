package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The one read behind every surface that reports a publisher's signature - the console's panel and the API alike.
 *
 * <p>It exists so the two cannot drift. They are the same capability seen twice, and a second copy of "resolve the
 * path to a coordinate, then read the version document's signature section" would be a second place for the answer
 * to change.
 *
 * <p>It re-verifies nothing and walks nothing. One point read of the coordinate the path resolves to, then one
 * bounded read of that version document's own section - no artifact body, no cryptography - so asking costs the same
 * on a repository holding ten million versions as on one holding ten, and the answer is the verdict the gate actually
 * reached rather than one recomputed against whatever keys are configured now.
 */
public final class SignatureSummaries {

    private SignatureSummaries() {
    }

    /**
     * The signature summary recorded for the file at a request path: its own, from the version document of the
     * coordinate version the path resolves to, else the version's - the weakest among its files - for a file whose
     * own was never recorded. A version's jar is signed though its POM is not, and asking about the jar answers the
     * jar.
     */
    public static Optional<SignatureSection.Summary> of(ArtifactStore store, String path) {
        Optional<ArtifactDescriptor> descriptor = new StoreRepositoryInventory(store).describe(path);
        if (descriptor.isEmpty() || descriptor.get().coordinate() == null || descriptor.get().version() == null) {
            return Optional.empty();
        }
        try {
            Optional<Section> section = MetadataProvider.installed().over(store).section(descriptor.get().ecosystem(),
                    descriptor.get().coordinate(), descriptor.get().version(), SignatureSection.TAG);
            SignatureSection.Summary own = SignatureSection.files(section).get(path);
            return own != null ? Optional.of(own) : SignatureSection.summary(section);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The signature summary recorded for a coordinate version.
     *
     * <p>Best-effort in the render-what-you-have sense: a coordinate that carries no version, or a read that fails,
     * yields empty, and the caller says there is none rather than failing the page. Empty is genuinely "nothing was
     * recorded" - a version whose document carries no signature section - which a caller must not present as a
     * signature that was checked and found wanting.
     */
    public static Optional<SignatureSection.Summary> of(ArtifactStore store, String ecosystem, String coordinate,
                                                        String version) {
        if (coordinate == null || coordinate.isEmpty() || version == null || version.isEmpty()) {
            return Optional.empty();
        }
        MetadataProvider provider = MetadataProvider.installed();
        try {
            return SignatureSection.summary(
                    provider.over(store).section(ecosystem, coordinate, version, SignatureSection.TAG));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
