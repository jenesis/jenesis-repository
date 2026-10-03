package build.jenesis.repository.compliance.signatures;

import build.jenesis.repository.compliance.ComplianceSettings;
import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Maintainers;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.compliance.TrustAware;
import build.jenesis.repository.gate.store.PublishInspection;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Re-derives a coordinate's recorded signature when the signature lands after the artifact it covers, as in a Maven
 * deploy, which sends the jar before its {@code .asc}; without this every signed release would read unsigned. The
 * gate's {@code QualityInspector.completes} re-assesses only held artifacts, so an accepted one needs this.
 *
 * <p>It records and does not enforce: under {@code signature-missing=ALLOW}, the default, a later tampered or untrusted
 * signature leaves the artifact serving with that verdict on its record, and acting on it is the {@code signature} hold
 * kind's retroactive sweep. A deployment that wants the late signature to decide holds the artifact until it arrives
 * ({@code signature-missing=QUARANTINE}).
 *
 * <p>It runs only for a path some format claims as signature material, which costs one {@code covers} call per
 * signature-declaring format (and an OCI manifest's head). When it fires it re-reads one artifact, streaming, and
 * writes one section of one version document. Without this module nothing records the fact or converges it.
 */
public final class SignatureCompletionObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // Every format's answer: a suffix-stripping covers() answers for any path, so an OCI cosign tag also gets
        // Swift's wrong answer, and a first match would depend on module-path order.
        Set<String> candidates = new LinkedHashSet<>();
        // Opened only by a format whose material names its subject in its own bytes (an OCI referrer).
        ArtifactSignatures.Signed published = () -> {
            Optional<String> hash = artifact.hash() != null ? Optional.of(artifact.hash())
                    : storedHash(artifact.path(), store);
            return hash.isPresent() ? new Blobs(store).open(hash.get()) : InputStream.nullInputStream();
        };
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (installed instanceof ArtifactSignatures format) {
                format.covers(artifact.path(), published).ifPresent(candidates::add);
            }
        }
        if (candidates.isEmpty()) {
            return;   // an ordinary publish: this path is nobody's signature material
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        String covered = null;
        Optional<ArtifactDescriptor> described = Optional.empty();
        for (String candidate : candidates) {
            Optional<ArtifactDescriptor> subject = inventory.describe(candidate);
            if (subject.isPresent() && subject.get().coordinate() != null && subject.get().version() != null) {
                covered = candidate;
                described = subject;
                break;
            }
        }
        if (covered == null) {
            return;   // the signature names something this deployment cannot place on a coordinate
        }
        Blobs blobs = new Blobs(store);
        // The stored bytes, not the served ones: a held artifact still has a signature.
        Publication publication = new Publication(store);
        // The serving pointer, else the quarantine one, since a late signature's artifact is often held for want of it.
        Optional<String> hash = storedHash(covered, store);
        if (hash.isEmpty()) {
            return;   // the sidecar arrived before its artifact; the artifact's own screening will read it
        }

        // The screens' effective lookup on the publishing thread, so runtime-configured keys apply.
        SignerTrust trust = SignerTrustProvider.trust(ComplianceSettings.lookup(store), store);
        QualityInspector inspector = ((TrustAware) new SignatureInspector()).withTrust(trust);
        // The screen's own sibling lookup over the stored view, which sees a sidecar while its subject is held.
        List<ComplianceGate.Subject> subjects = inspector.inspectArtifact(covered,
                new StoredContent(blobs, hash.get()),
                PublishInspection.siblings(publication.heldContentOf(hash.get()))).subjects();
        List<ComplianceGate.Signature> signatures = subjects.stream()
                .flatMap(subject -> subject.signatures().stream())
                .toList();
        Optional<ComplianceGate.Signature> summary = ComplianceGate.Signature.summarising(signatures);
        if (summary.isEmpty()) {
            return;
        }
        // Where a late signature's continuity is learned, as for an accepted publish.
        for (ComplianceGate.Signature signature : signatures) {
            if (signature.signer() == null) {
                continue;
            }
            if (summary.get().trusted() && signature.trusted()) {
                trust.observed(described.get().ecosystem(), described.get().coordinate(),
                        described.get().version(), signature.signer(), Instant.now());
            } else if (signature.outcome() == ComplianceGate.Signature.Outcome.UNTRUSTED
                    && signature.keySource() == null) {
                // No source held the key: a discovery source fetches it by id or by the recorded maintainers.
                trust.wanted(signature.signer(), covered, Maintainers.named(store, described.get().ecosystem(),
                        described.get().coordinate()), Instant.now());
            }
        }
        new StoreRepositoryInventory(store)
                .recording(described.get().ecosystem(), described.get().coordinate(), described.get().version(),
                        described.get().prerelease(), Instant.now())
                .file(covered)
                .signature(summary.get().outcome().name(),
                        summary.get().signer() == null ? null : summary.get().signer().wire(),
                        summary.get().quality() == null ? null : summary.get().quality().grade().name(),
                        summary.get().location(), summary.get().keySource(), summary.get().details())
                .commit();
    }

    /**
     * The content hash of the artifact stored at a covered path: the serving pointer, the held one, or a blobs-namespace
     * layout's own key. Empty when the artifact has not arrived yet, whose own screening then covers it.
     */
    public static Optional<String> storedHash(String covered, ArtifactStore store) throws IOException {
        Publication publication = new Publication(store);
        Optional<String> hash = publication.blob(covered);
        if (hash.isEmpty()) {
            hash = publication.blob("/quarantine" + covered);
        }
        if (hash.isEmpty()) {
            Blobs blobs = new Blobs(store);
            for (RepositoryFormat installed : RepositoryFormat.installed()) {
                if (installed instanceof BlobLayout layout) {
                    Optional<String> key = layout.servingKey(covered, store);
                    if (key.isPresent()) {
                        hash = blobs.locate(key.get()).map(Blobs.Located::hash);
                        break;
                    }
                }
            }
        }
        return hash;
    }

    /** The covered artifact's stored bytes, reopened per pass so the verify streams rather than buffers. */
    private record StoredContent(Blobs blobs, String hash) implements QualityInspector.Content {

        @Override
        public long size() throws IOException {
            // The blob's real size, from the store's stat (Blobs.size expects a pointer key), so the inspector can tell
            // an oversized artifact before reading it.
            return blobs.store().size("blobs/" + hash);
        }

        @Override
        public InputStream open() throws IOException {
            return blobs.open(hash);
        }
    }
}
