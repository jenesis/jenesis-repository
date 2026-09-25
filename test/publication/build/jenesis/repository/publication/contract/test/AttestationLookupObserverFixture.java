package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The attestation lookup: on the publish of an artifact whose format expects a Sigstore bundle beside it, it asks the
 * attestation store the operator's dial names for that ecosystem by the artifact's digest, and keeps the bundle it is
 * handed as the sidecar. The dial ships empty and the kit's artifacts belong to no format, so under the kit it asks
 * nobody and records nothing.
 */
final class AttestationLookupObserverFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    private static final String WHY = "it asks an attestation store only for an ecosystem the signature-attestation-lookup dial "
            + "names - empty by default - and only for an artifact whose format expects a Sigstore bundle beside it, "
            + "and the kit's artifacts belong to no format; HomebrewAttestationLookupE2ETest drives the lookup end to "
            + "end, from the store's answer to the kept sidecar and the verdict re-derived over it";

    @Override
    public String hook() {
        return "attestation-lookup";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.compliance.signatures.AttestationLookupObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    /** Where it writes when it does fire: the kept bundle, as a publish pointer and its blob, or under the serving key
     *  of whichever installed layout keeps sidecars in a blob root of its own. */
    @Override
    public List<String> namespaces() {
        List<String> spaces = new ArrayList<>(List.of("publish", "blobs"));
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (format instanceof BlobLayout layout) {
                spaces.addAll(layout.blobRoots());
            }
        }
        return List.copyOf(spaces);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) {
        return Map.of();
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();
    }

    /** The hook's own precondition, as {@link #whyNothingOnPublish} states it. */
    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public void repair(ArtifactStore store) {
        // Nothing recorded, so nothing to re-derive.
    }

    @Override
    public String whyNothingOnPublish() {
        return WHY;
    }

    @Override
    public Map<PublicationHookContract.Property, String> unsupported() {
        return Map.of(
                PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, WHY,
                PublicationHookContract.Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, WHY,
                PublicationHookContract.Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, WHY,
                PublicationHookContract.Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, WHY);
    }
}
