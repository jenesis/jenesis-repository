package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code CachedHoldingObserver}: the inventory's record of a copy a pull-through cached from an upstream - the
 * version document's {@code cached} section and a row of the newest-first {@code cached} index.
 *
 * <p><b>It has no publish leg.</b> {@code onPublished} is an explicit no-op: a publish is a release, and a release is
 * recorded by the publish gate, not here. Its only leg is {@code onCached}, which a pull-through fires after a fill
 * and which the kit, driving publishes, never reaches - so over the kit's published list the surface stays empty, and
 * the properties whose assertion needs a publish to move it are excluded here. The leg itself, its idempotence, its
 * refusal to record over a release and the repairs that heal a lost notice (the reconcile and the inventory back-fill
 * reading a fill's origin trail) are proven in the inventory module's own suite, and a fill driving it end to end in
 * the pull-through's.
 */
final class CachedHoldingFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    private static final String NO_PUBLISH_LEG =
            "the hook declares no publish leg: onPublished is an explicit no-op, because a publish is a release and "
                    + "the gate records it. Its surface moves only on onCached, which a pull-through fires after a "
                    + "fill and a publish-driven check never reaches. Proven instead in the inventory suite's "
                    + "CachedHoldingsTest - the notice, the repairs that heal a lost one from the fill's origin "
                    + "trail - and in the pull-through's PullThroughFillNoticeTest.";

    @Override
    public String hook() {
        return "inventory-cached-holding";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.inventory.CachedHoldingObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    /** The newest-first index it writes a row to; the section it writes lives in the version documents, which the
     *  metadata store's own manifest declares. */
    @Override
    public List<String> namespaces() {
        return List.of(StoreRepositoryInventory.cachedRoot());
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, NO_PUBLISH_LEG,
                Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, NO_PUBLISH_LEG,
                Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, NO_PUBLISH_LEG,
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, NO_PUBLISH_LEG);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        Hooks.names(store, StoreRepositoryInventory.cachedRoot()).forEach(name -> rows.put(name, "cached"));
        return rows;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();   // a publish is a release: no copy is recorded for it
    }

    @Override
    public void repair(ArtifactStore store) {
        throw new UnsupportedOperationException(NO_PUBLISH_LEG);
    }

    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public String whyNothingOnPublish() {
        return NO_PUBLISH_LEG;
    }
}
