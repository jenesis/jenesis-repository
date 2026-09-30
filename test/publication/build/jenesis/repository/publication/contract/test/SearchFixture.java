package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import build.jenesis.repository.hooks.testkit.Deployment;
import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.hooks.testkit.Pass;

/**
 * {@code SearchPublicationObserver}: one coalesced {@code DirtyIndexFeed} marker per coordinate, repaired by
 * {@code SearchIndexTask}'s full rebuild from the version documents.
 *
 * <p><b>The kit cannot move this surface, and that is a fact about the kit, not about the hook.</b> The observer
 * marks nothing for a publish whose descriptor carries no coordinate - deliberately, because a checksum or a
 * generated metadata file has nothing the search index carries - and
 * {@code PublicationHookContract} publishes {@code ArtifactDescriptor.at("kit", path)} through a
 * {@code Visibility.at(...)} that sets no {@code described}, so <em>every</em> artifact the kit commits reaches this
 * hook as exactly that shape. The properties whose assertion needs the surface to move are therefore excluded here
 * and proven in {@link CoordinateKeyedObserverTest}, which drives the same hook through {@code Publication.published}
 * - the free core's own declared after-commit seam, the one an ingress edge uses - with a coordinate-bearing
 * descriptor, and then runs this fixture's repair leg over it.
 */
final class SearchFixture implements PublicationHookFixture.Observer, Deployment {

    /** {@code SearchIndex.DIRECTORY}; the class is package-private, so the key is named rather than imported. */
    static final String SPACE = "index/search";

    /** {@code SearchIndex.MANIFEST} - the object whose presence tells the observer an index exists to mark against. */
    static final String MANIFEST = SPACE + "/current";

    /** A format-2 manifest with an empty generation: enough for the observer's gate and for the task to read it as a
     *  built index rather than a bootstrap. */
    private static final String BUILT_MANIFEST =
            "jenesis-search 1\ngeneration 1\nformat 2\ndocuments 0\nchecksum \n";

    /**
     * A coordinate-bearing descriptor, because this hook keys on the neutral ecosystem/coordinate/version triple
     * and skips a publish that carries none. The kit's default is coordinate-less - a real shape, and what a
     * checksum or a generated sidecar looks like - which is why this hook could not be driven through the kit at
     * all until the fixture was allowed to say otherwise. The coordinate is derived from the path so every publish
     * the kit makes is one this hook can see, whatever path a check picks.
     */
    @Override
    public ArtifactDescriptor describe(String path) {
        return PublicationHookContract.coordinated(path, "com.example" + path.replace('/', ':'), "1.0.0");
    }

    /**
     * The two remaining exclusions, and a narrower claim than the one they replaced.
     *
     * <p>Both surviving properties end in this fixture's {@code repair} leg, which rebuilds the index by walking
     * the durable published set. The kit publishes through {@code Publication.commit} into a bare store: there is
     * no inventory module on its graph to record a published row, so the walk the repair performs finds nothing
     * and converges on an empty index while the check expects the coordinates it published. That is a property of
     * what a kit deployment contains, not of this hook - the hook's own repair is exercised for real in
     * {@code CoordinateKeyedObserverTest}, over a store an inventory has written.
     *
     * <p>Stated separately from the coordinate gate it replaced because the two are different claims. The old one
     * said the kit could not reach this hook, which is no longer true for any of the four. This one says two of
     * them need durable state the kit does not build, which is a gap in the kit's deployment rather than in its
     * reach, and is what a future entry would have to close.
     */
    private static final String NO_DURABLE_TRUTH_TO_REPAIR_FROM =
            "the check ends in the repair leg, which rebuilds this index by walking the durable published set - and "
                    + "a kit deployment has no inventory module to write one, so the repair converges on an empty "
                    + "index rather than on what the kit published. The hook's repair is exercised over a real "
                    + "store in CoordinateKeyedObserverTest; what is missing here is the deployment, not the reach.";

    @Override
    public String hook() {
        return "search-publication";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.search.lucene.SearchPublicationObserver";
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
        // The bootstrap gate: until the first sweep has written a manifest, a publish is deliberately NOT marked,
        // because that sweep is a full rebuild from truth. A fixture that skipped this would be driving the guard.
        Hooks.upsert(store, MANIFEST, BUILT_MANIFEST);
    }

    @Override
    public Map<Property, String> unsupported() {
        // Was four, all reading "the kit cannot reach this hook at all": it committed a coordinate-less
        // descriptor for every artifact and this hook marks nothing for a publish with no coordinate. The fixture
        // now declares the shape it needs, so two of the four are checks rather than notes.
        return Map.of(
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, NO_DURABLE_TRUTH_TO_REPAIR_FROM,
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, NO_DURABLE_TRUTH_TO_REPAIR_FROM);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        for (DirtyIndexFeed.Entry entry : new DirtyIndexFeed(store, SPACE).pending()) {
            // The marker's version is a wall-clock stamp - what "may legitimately differ between two converged runs" -
            // so the projection keeps the coordinate and the direction and drops the stamp.
            rows.put(entry.coordinate(), entry.removed() ? "removed" : "touched");
        }
        return rows;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        for (ArtifactDescriptor artifact : published) {
            if (artifact.coordinate() != null && artifact.version() != null) {
                converged.put(SearchIndexTask.coordinateKey(
                        artifact.ecosystem(), artifact.coordinate(), artifact.version()), "touched");
            }
        }
        return converged;
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        // The concrete pass: SearchIndexTask's full rebuild, forced onto every pass of a repository that has its
        // index on, which re-derives the coordinate set from the version documents and only then GCs the feed with a
        // pre-enumeration cutoff.
        new SearchIndexTask(Duration.ofMinutes(10), null)
                .repository(Pass.over(store).with("full-text-search", "true").with("search-incremental", "false"));
    }
}
