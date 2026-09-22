package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The one read behind every surface that reports a publisher's signature - the console's panel and the API alike.
 *
 * <p>It exists so the two cannot drift. They are the same capability seen twice, and a second copy of "resolve the
 * path to a coordinate, then read the version document's signature section" is a second place for the answer to
 * change: this codebase has already paid for that shape more than once, most memorably with an authorization matrix
 * written twice and kept in step by a comment naming a class that had since been renamed.
 *
 * <p>It re-verifies nothing and walks nothing. One point read of the coordinate the path resolves to, then one
 * bounded read of that version document's own section - no artifact body, no cryptography - so asking costs the same
 * on a repository holding ten million versions as on one holding ten, and the answer is the verdict the gate actually
 * reached rather than one recomputed against whatever keys are configured now.
 */
public final class SignatureSummaries {

    private SignatureSummaries() {
    }

    /** The signature summary recorded for whatever coordinate version a request path resolves to. */
    public static Optional<SignatureSection.Summary> of(ArtifactStore store, String path) {
        Optional<ArtifactDescriptor> descriptor = new StoreRepositoryInventory(store).describe(path);
        if (descriptor.isEmpty()) {
            return Optional.empty();
        }
        return of(store, descriptor.get().ecosystem(), descriptor.get().coordinate(), descriptor.get().version());
    }

    /**
     * The signature summary recorded for a coordinate version.
     *
     * <p>Best-effort in the render-what-you-have sense: a deployment without the metadata module, a coordinate that
     * carries no version, or a read that fails yields empty, and the caller says there is none rather than failing
     * the page. Empty is genuinely "nothing was recorded" - a version published before signatures were checked here -
     * which a caller must not present as a signature that was checked and found wanting.
     */
    public static Optional<SignatureSection.Summary> of(ArtifactStore store, String ecosystem, String coordinate,
                                                        String version) {
        if (coordinate == null || coordinate.isEmpty() || version == null || version.isEmpty()) {
            return Optional.empty();
        }
        Optional<MetadataProvider> provider = MetadataProvider.installed();
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        try {
            return SignatureSection.summary(
                    provider.get().over(store).section(ecosystem, coordinate, version, SignatureSection.TAG));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
