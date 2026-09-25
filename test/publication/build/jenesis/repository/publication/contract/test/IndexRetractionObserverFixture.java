package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.index.PublishedIndex;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The published index's withhold-feed subscriber: a withhold or a cleared withhold raises one coalescing retraction
 * flag, which the next index pass reads to force the rebuild that drops a held path from the immutable chain (or
 * restores a cleared one). A publish raises nothing - the pass indexes publishes incrementally - so the projection is
 * the flag alone, raised exactly when something was withheld.
 */
final class IndexRetractionObserverFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    @Override
    public String hook() {
        return "index-retraction";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.index.IndexRetractionObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(PublishedIndexKeys.PREFIX);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return new PublishedIndex(store).retraction().peek().isPresent() ? Map.of("retraction", "raised") : Map.of();
    }

    /** A publish is not a withhold transition, so it raises nothing. */
    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();
    }

    /** Any withhold raises the one flag, however many artifacts it held - the rebuild it forces covers them all. */
    @Override
    public Map<String, String> withheld(List<ArtifactDescriptor> withheld) {
        return withheld.isEmpty() ? Map.of() : Map.of("retraction", "raised");
    }

    /** The hook's own precondition: only a withhold transition raises the flag, never an accepted publish. */
    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public String whyNothingOnPublish() {
        return "it records nothing on a publish - its one surface is the flag a withhold raises, which the withheld "
                + "leg drives and asserts - so the clauses about what it records for a publish have nothing to act "
                + "on; the rebuild the flag forces, and the periodic rebase that retracts without it, are "
                + "PublishedIndexHardeningTest's";
    }

    @Override
    public Map<PublicationHookContract.Property, String> unsupported() {
        String reason = whyNothingOnPublish();
        return Map.of(
                PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, reason,
                PublicationHookContract.Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, reason,
                PublicationHookContract.Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, reason,
                PublicationHookContract.Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, reason);
    }

    @Override
    public void repair(ArtifactStore store) {
        // A publish-only store converges to no flag, which is where it already is: a lost flag is healed not by
        // raising it again but by the index pass's periodic rebase from truth, which retracts without it.
    }
}
