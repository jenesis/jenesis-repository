package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The signature completion: when signature material a format claims lands after the artifact it covers - a Maven
 * deploy sends the jar before its {@code .asc} - it re-derives that coordinate's recorded signature. The kit's paths
 * are no format's signature material, so under the kit it records nothing.
 */
final class SignatureCompletionObserverFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    private static final String WHY = "it fires only for a path some installed format claims as signature material, and re-derives "
            + "the covered coordinate's signature section - the kit's paths are no format's, so there is nothing to "
            + "complete; SignatureRecordedE2ETest and the ecosystem matrix's SIGNATURE_VERIFIED row drive it through "
            + "real deploys, where the signature lands after its artifact";

    @Override
    public String hook() {
        return "signature-completion";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.compliance.signatures.SignatureCompletionObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    /** Where it writes when it does fire: the signature section of the covered version's record. */
    @Override
    public List<String> namespaces() {
        return List.of(MetadataKey.PREFIX);
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
