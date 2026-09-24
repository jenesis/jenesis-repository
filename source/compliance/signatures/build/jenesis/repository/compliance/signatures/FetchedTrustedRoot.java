package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Sigstore trusted root this deployment fetched rather than was given: one stored document per repository
 * ({@code discovered/sigstore-trusted-root}), kept current by {@link TrustedRootTask} and read here by point read
 * as the {@code sigstore} material whenever an operator pasted none.
 *
 * <p>It exists because the alternative was a feature that is inert until somebody pastes a document. A Sigstore
 * bundle can be verified against nothing but a trusted root - the certificate authorities that may have issued its
 * signing certificate and the transparency logs whose entries prove when it was signed - and the root for the
 * public-good instance is a published document that changes about as often as a CA rotates. A deployment that says
 * nothing now verifies bundles against it; one that pastes a root keeps using theirs, and the pass then fetches
 * nothing at all.
 *
 * <p><b>Holding a root still trusts nobody</b>, which is why {@link #trusts} is unconditionally false here. The
 * public-good Fulcio issues a certificate to anyone its issuers will authenticate, so a root says only that a
 * bundle really was signed by the identity it names, at the time the log recorded - and who may sign <em>this</em>
 * coordinate stays an operator's pin or the provenance of the repository the artifact declares. The scheme says as
 * much by answering {@code false} to {@code materialNamesSigner}, so a bundle this part's material verified is put
 * to every other part for admission.
 *
 * <p>Nothing here reaches the network: the document is read from the store, and the pass that fills it runs on the
 * maintenance cadence - which is the read-purity clause the trust provider carries, for the same reason a key is
 * never fetched at verification time.
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

    /** The fetched root, or empty when the pass has not stored one - which is every deployment until its first pass. */
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
        // Nothing is learned from a root: what a coordinate's history expects is the continuity part's.
    }

    @Override
    public String source() {
        return SOURCE;
    }
}
