package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.dependents.DependentsIndex;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import build.jenesis.repository.hooks.testkit.Deployment;
import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.hooks.testkit.ServedOnly;

/**
 * {@code DependentsPublicationObserver}: one coalesced {@code DirtyIndexFeed} marker per <em>blob hash</em>, repaired
 * by {@code DependentsIndex}'s full {@code blobs/} walk-inversion.
 *
 * <p><b>The projection is inverted back through the pointer tree, and that is the honest normalisation.</b> The feed
 * is keyed by content hash because the reverse-dependency graph is derived from the stored blobs (each blob's embedded
 * SBOM names a root coordinate), never from coordinate pointers - but the kit declares convergence over a list of
 * <em>descriptors it has not yet committed</em>, which carry no blob identity at all. So the fixture translates each
 * marker back into the request paths whose {@code publish/} pointer names that blob, which is the comparable view the
 * two sides share and which drops nothing the hook decided.
 *
 * <p><b>What genuinely cannot be shown here.</b> Every artifact the shared contract publishes carries the same body,
 * hence one blob, hence one marker: after the first publish the surface already covers every later one, so a call lost
 * on the second publish leaves <em>no trace</em> - not because the containment hid it, but because the coalescing this
 * hook exists for had already recorded the work. Those two properties are excluded with that reason and proven in
 * {@link CoordinateKeyedObserverTest} over two distinct bodies.
 */
final class DependentsFixture implements PublicationHookFixture.Observer, Deployment, ServedOnly {

    /** {@code DependentsStore.PREFIX}; the class is package-private, so the key is named rather than imported. */
    static final String SPACE = "dependents";

    /** {@code DependentsStore.BUILT} - the marker whose presence tells the observer an index exists to mark against. */
    static final String BUILT = SPACE + "/built";

    private static final String ONE_BLOB =
            "the feed coalesces by blob hash, and every artifact the kit publishes carries the same body - so the "
                    + "marker written by the first publish already covers the second, and a call lost on the second "
                    + "can leave no durable trace to tell it apart from a delivered one. That is the coalescing this "
                    + "hook exists for, not a containment that hid a failure. Proven instead in "
                    + "CoordinateKeyedObserverTest, which drives Publication.published (the free core's own "
                    + "after-commit seam) with two distinct bodies, drops one call, and runs this fixture's repair.";

    @Override
    public String hook() {
        return "dependents-publication";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.dependents.DependentsPublicationObserver";
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
    public void deploy(ArtifactStore store) throws IOException {
        // The bootstrap gate: until the first sweep has inverted every blob, a publish is deliberately NOT marked,
        // because that sweep rebuilds from truth anyway. A fixture that skipped this would be driving the guard.
        Hooks.upsert(store, BUILT, Instant.parse("2026-08-01T00:00:00Z").toString());
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, ONE_BLOB,
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, ONE_BLOB);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, List<String>> byHash = new TreeMap<>();
        for (String path : Hooks.published(store)) {
            Hooks.pointer(store, path).ifPresent(hash -> byHash.computeIfAbsent(hash, _ -> new ArrayList<>()).add(path));
        }
        Map<String, String> rows = new TreeMap<>();
        for (DirtyIndexFeed.Entry entry : new DirtyIndexFeed(store, SPACE).pending()) {
            // The marker's version is a wall-clock stamp - what may legitimately differ between two converged runs -
            // so it is dropped and the direction kept.
            String direction = entry.removed() ? "removed" : "touched";
            List<String> paths = byHash.get(entry.coordinate());
            if (paths == null) {
                rows.put("blob:" + entry.coordinate(), direction);   // a removal: no pointer resolves it any more
            } else {
                paths.forEach(path -> rows.put(path, direction));
            }
        }
        return rows;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        published.forEach(artifact -> converged.put(artifact.path(), "touched"));
        return converged;
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        // The concrete pass: the full blobs/ walk-inversion DependentsIndexTask runs as its reconcile, over durable
        // store truth and with no view of anything the live events did or did not deliver.
        new DependentsIndex(store).rebuild();
    }

    @Override
    public String whyServedOnly() {
        return "the projection is derived by inverting the served pointer tree, and a never-published variant subject "
                + "is not in it - so a row appended under one never reaches the surface this fixture reads";
    }
}
