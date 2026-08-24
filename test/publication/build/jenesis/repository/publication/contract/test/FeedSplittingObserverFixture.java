package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/** The fixture for {@link FeedSplittingObserver}: the one subject in the kit that can exhibit a conflated feed, and
 *  therefore the one that makes {@code A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG} falsifiable. */
class FeedSplittingObserverFixture implements PublicationHookFixture.Observer {

    @Override
    public String hook() {
        return "kit-feeds";
    }

    @Override
    public String providerClass() {
        return FeedSplittingObserver.class.getName();
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(FeedSplittingObserver.SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, FeedSplittingObserver.SPACE);
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        published.forEach(a -> converged.put(Keys.slug(a.path()), FeedSplittingObserver.PUBLISHED));
        return converged;
    }

    @Override
    public Map<String, String> withheld(List<ArtifactDescriptor> withheld) {
        Map<String, String> rows = new TreeMap<>();
        withheld.forEach(a -> rows.put(Keys.slug(a.path()), FeedSplittingObserver.WITHHELD));
        return rows;
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        PublicationObserver fresh = create();
        for (String path : Keys.published(store)) {
            String hash = Keys.read(store, "publish" + path).map(String::trim).orElse(null);
            fresh.onPublished(ArtifactDescriptor.at("kit", path).withBlob(hash, -1L), store);
        }
    }
}
