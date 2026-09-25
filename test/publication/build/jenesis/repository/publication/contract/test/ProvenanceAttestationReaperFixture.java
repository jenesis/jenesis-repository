package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The provenance-attestation reaper: it records nothing on a publish - an attestation is minted lazily on first read -
 * and on an unpublish deletes the attestation cached for that blob at that path. The projection is the cache space,
 * which a publish leaves empty.
 */
final class ProvenanceAttestationReaperFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    /** The attestation cache's key space - {@code ProvenanceAttestationCache.PREFIX}, whose package is not exported
     *  here. */
    private static final String SPACE = "provenance-attestation";

    private static final String WHY = "it records nothing on a publish - an attestation is minted on first read - "
            + "and its one effect is deleting the attestation cached for an unpublished pointer, which "
            + "ProvenanceCacheTest and KeySpaceReclamationTest drive; the clauses about what it records for a "
            + "publish have nothing to act on";

    @Override
    public String hook() {
        return "provenance-reaper";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.compliance.web.ProvenanceAttestationReaper";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, SPACE);
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();
    }

    /** The hook's own precondition: a publish mints nothing. */
    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public void repair(ArtifactStore store) {
        // Nothing to re-derive: a missed reclamation leaves a stale attestation the operator purge and the next
        // read's re-sign both settle.
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
