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
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Re-derives a coordinate's recorded signature when the signature itself lands <em>after</em> the artifact it covers.
 *
 * <h2>Why this is needed at all</h2>
 *
 * A Maven deploy is several requests and the client sends the artifact first: the jar is screened while its
 * {@code .asc} is still in flight, so at the only moment the gate looks at it, the artifact genuinely carries no
 * signature. Without this, <em>every properly signed release</em> would be recorded as unsigned - the capability would
 * be exactly wrong in the ordinary case and right only in the rare one.
 *
 * <p>The gate already has a seam for this - {@code QualityInspector.completes} - but it re-assesses artifacts that are
 * <b>held</b>, which is the licence dimension's problem: a POM arriving late releases a jar quarantined for an unknown
 * licence. An artifact that was <em>accepted</em> is not in that queue, and until a signature dimension exists to hold
 * an unsigned artifact, nothing brings the late sidecar back to it. So the fact needs its own convergence, and it
 * belongs to the module that owns the fact rather than to the format-agnostic screen.
 *
 * <h2>What it does not do</h2>
 *
 * It records; it does not enforce. An artifact accepted under {@code signature-missing=ALLOW} - the shipped default,
 * because a signature still in flight is not an absent one - and then followed by a signature that proves tampered
 * or untrusted keeps serving, with that verdict on its version record. Enforcing evidence that arrives after
 * acceptance is a retroactive decision over what is already published, the sweep the {@code signature} hold kind
 * exists for, and not a publish-time one. A deployment that wants the late signature to decide holds the artifact
 * until it arrives ({@code signature-missing=QUARANTINE}); the gate's own completion then re-assesses the held
 * artifact over its sidecar and releases only what it now allows. The soak drives the signature fates under that
 * posture for exactly this reason: under the default, a tampered signature changes nothing a client sees.
 *
 * <h2>What it costs</h2>
 *
 * It runs only when the published path is signature material some installed format claims - a {@code .asc}, not a jar -
 * so an ordinary publish pays one {@code covers} call per signature-declaring format and nothing else. When it does
 * fire it re-reads one artifact, streaming: the signature is a few hundred bytes and the body is fed through a digest
 * in bounded chunks, so a multi-gigabyte package costs no heap. It writes one section of one version document.
 *
 * <p>It is deliberately an observer rather than part of the screen: the screen names no dimension, and a format-blind
 * gate that knew about signatures in order to converge them would be the same shape this codebase removes elsewhere.
 * Absent the module, nothing observes and nothing converges - which is the correct degradation, because nothing is
 * recording the fact either.
 */
public final class SignatureCompletionObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // EVERY format that claims to cover this path, not the first one discovery happened to yield.
        //
        // A detached sidecar's covers() strips its suffix and answers; it never asks whether the path is under its
        // own route. So a path ending .sig is answered by Swift's declaration wherever it sits - including an OCI
        // cosign signature tag, where the right answer is the manifest DIGEST and the suffix strip gives the tag
        // with ".sig" removed. Taking the first match made which answer won a property of the module path's
        // ordering, and the losing case is silent: the wrong subject does not describe, the observer returns, and
        // an artifact's signature is simply never re-derived. That is the ecosystem fan-out rule this codebase
        // already states for advisory lookups - ask all of them and union the answers - applied where it was not.
        Set<String> candidates = new LinkedHashSet<>();
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (installed instanceof ArtifactSignatures format) {
                format.covers(artifact.path()).ifPresent(candidates::add);
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
        // blob(), not located(): located answers "which bytes would a GET serve", which respects a hold, and this is
        // re-deriving a fact about the bytes that are stored. A held artifact still has a signature, and recording
        // nothing for it would read as "nobody signed this".
        Publication publication = new Publication(store);
        // The serving pointer first, then the quarantine one. A signature that arrives after its artifact very often
        // arrives after that artifact was HELD for the want of it - which is the secure floor working - and a held
        // artifact has no serving pointer. Reading the held blob back under "/quarantine" is exactly how
        // ComplianceScreen re-assesses a jar whose POM landed late, and the same artifact is reachable the same way
        // here. Without it the fact is derived for the artifacts that did not need it and skipped for the ones that
        // did.
        Optional<String> hash = storedHash(covered, store);
        if (hash.isEmpty()) {
            return;   // the sidecar arrived before its artifact; the artifact's own screening will read it
        }

        // The same effective lookup the screens use, not the boot environment: this observer runs on the publishing
        // thread, so the tenant is still bound and the keys an operator configured at runtime are the ones that
        // apply. Reading the process configuration here instead was the defect that made a correctly configured
        // deployment report every signature untrusted - and it hid behind the inline path, because a held artifact
        // is recorded by THIS observer rather than by the screen. The composed trust with nothing installed is NONE,
        // so this needs no presence check of its own.
        SignerTrust trust = SignerTrustProvider.trust(ComplianceSettings.lookup(), store);
        QualityInspector inspector = ((TrustAware) new SignatureInspector()).withTrust(trust);
        // The screen's own sibling lookup over the stored view, not a second one written here: it carries the bounded
        // read, the blobs-namespace formats' own serving keys, and - through heldContentOf - the stored pointer rather
        // than the serving one, which is what makes a sidecar visible while its subject is held. A private copy had
        // none of the second and would have gone blind on every format that keeps its own key space.
        List<ComplianceGate.Subject> subjects = inspector.inspectArtifact(covered,
                new StoredContent(blobs, hash.get()),
                ComplianceScreen.siblings(publication.heldContentOf(hash.get()))).subjects();
        List<ComplianceGate.Signature> signatures = subjects.stream()
                .flatMap(subject -> subject.signatures().stream())
                .toList();
        Optional<ComplianceGate.Signature> summary = ComplianceGate.Signature.summarising(signatures);
        if (summary.isEmpty()) {
            return;
        }
        // The late sidecar is where Maven's continuity is learned: the artifact was screened before its signature
        // existed, so the screen observed nothing, and this re-derivation is the first to see who signed it. A
        // version whose every signature verified by a trusted signer is observed here as an accepted publish is.
        for (ComplianceGate.Signature signature : signatures) {
            if (signature.signer() == null) {
                continue;
            }
            if (summary.get().trusted() && signature.trusted()) {
                trust.observed(described.get().ecosystem(), described.get().coordinate(),
                        described.get().version(), signature.signer(), Instant.now());
            } else if (signature.outcome() == ComplianceGate.Signature.Outcome.UNTRUSTED
                    && signature.keySource() == null) {
                // No source held this signer's key: a discovery source, where the operator named one, fetches it -
                // by its id, or by the maintainers the coordinate's accepted publish recorded, since the artifact
                // was screened one request before its signature and this re-derivation reads no metadata itself.
                trust.wanted(signature.signer(), covered, Maintainers.named(store, described.get().ecosystem(),
                        described.get().coordinate()), Instant.now());
            }
        }
        new StoreRepositoryInventory(store)
                .recording(described.get().ecosystem(), described.get().coordinate(), described.get().version(),
                        described.get().prerelease(), Instant.now())
                .signature(summary.get().outcome().name(),
                        summary.get().signer() == null ? null : summary.get().signer().wire(),
                        summary.get().quality() == null ? null : summary.get().quality().grade().name(),
                        summary.get().location(), summary.get().keySource(), summary.get().details())
                .commit();
    }

    /**
     * The content hash of the artifact stored at a covered path, wherever this deployment keeps it: the serving
     * pointer under {@code publish/}, the held one under {@code /quarantine}, or - for a blobs-namespace format
     * (Helm, Swift), which keeps its pointers under its own roots and never under {@code publish/} - the key the
     * owning layout serves the path from, found the way a sibling read finds it. Empty when the artifact has not
     * arrived, which is the "sidecar first" order every ingress allows and the artifact's own screening then covers.
     * Public because it is the one blobs-namespace-aware resolution of a covered path, and the thing a test of the
     * late-sidecar order proves without a consolidated metadata store to record into.
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
            // Real, not -1: the inspector decides from it whether an artifact is past the inspection bound, and an
            // unknown size would make it start reading one it was never going to finish. The store's own stat of
            // the blob: Blobs.size resolves a POINTER at the key it is given, and handing it the blob's key read the
            // whole artifact as if it were a pointer body and answered -1 for every artifact - the unknown this
            // comment says must not happen, unnoticed because nothing asserted the figure.
            return blobs.store().size("blobs/" + hash);
        }

        @Override
        public InputStream open() throws IOException {
            return blobs.open(hash);
        }
    }
}
