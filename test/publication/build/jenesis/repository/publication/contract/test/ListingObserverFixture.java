package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The fixture of a format's stored-listing observer - the after-commit hook that keeps a {@link StoredListing} in
 * step with the transitions that happen off the format's own publish path. It records nothing on a publish: the
 * listing a publish changes is written by the format's publish itself, and the observer's {@code onPublished} is a
 * no-op. Its work is the withhold, release, mark and delete legs, which re-decide one entry of a listing the format
 * owns; the kit's {@code kit} ecosystem and {@code /kit/...} paths belong to no format, so under this kit the observer
 * writes nothing at all - which is what the properties below are held to, and why the recording-shaped properties
 * are excluded with the format contract's held-version leg named as where the surface is proven.
 */
abstract class ListingObserverFixture implements PublicationHookFixture.Observer {

    private static final String PROVEN = "this observer records nothing on a publish - the format's own publish writes "
            + "its listing - and re-decides one entry on a withhold, release, mark or removal; the format contract's "
            + "held-version leg proves that surface over the format's own paths";

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(StoredListing.ROOT.substring(0, StoredListing.ROOT.length() - 1));
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, StoredListing.ROOT.substring(0, StoredListing.ROOT.length() - 1));
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();   // a kit publish belongs to no format, so no listing ever names it
    }

    @Override
    public void repair(ArtifactStore store) {
        // The repair is the rebuild pass regenerating every stored listing (StoredListing.rebuildAll) - nothing to
        // regenerate for kit publishes, which no listing names.
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, PROVEN,
                Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, PROVEN,
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, PROVEN,
                Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, PROVEN);
    }
}
