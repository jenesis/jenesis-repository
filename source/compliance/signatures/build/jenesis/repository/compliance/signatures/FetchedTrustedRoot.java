package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Sigstore trusted root this deployment fetched ({@link #DOCUMENT}), kept current by {@link TrustedRootTask} and
 * read by point read as the {@code sigstore} material where an operator pasted none.
 *
 * <p>Holding a root trusts nobody, so {@link #trusts} is false: the public-good Fulcio certifies anyone its issuers
 * authenticate, and who may sign a coordinate stays a pin or the declared repository's provenance. No network call.
 */
final class FetchedTrustedRoot implements SignerTrust {

    /** The stored document: the trusted root as its publisher serves it, bytes unchanged. */
    static final String DOCUMENT = "discovered/sigstore-trusted-root";

    /** The source name a signature verified against the fetched root is reported with, where nothing admitted it. */
    static final String SOURCE = "sigstore-trusted-root";

    private final ArtifactStore store;

    FetchedTrustedRoot(ArtifactStore store) {
        this.store = store;
    }

    /** The fetched root, or empty before the pass stored one. */
    static Optional<byte[]> stored(ArtifactStore store) throws IOException {
        return store.readVersioned(DOCUMENT).map(ArtifactStore.Versioned::content).filter(root -> root.length > 0);
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        if (!SignerIdentity.SIGSTORE.equals(scheme)) {
            return Optional.empty();
        }
        try {
            return stored(store);
        } catch (IOException unreadable) {
            return Optional.empty();   // no material is the fail-closed direction: nothing verifies against it
        }
    }

    @Override
    public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
        return false;
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        return Optional.empty();
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when) {
        // Nothing is learned from a root.
    }

    @Override
    public String source() {
        return SOURCE;
    }
}
