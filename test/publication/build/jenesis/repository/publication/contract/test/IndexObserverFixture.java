package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/** The {@link IndexObserver} fixture: a best-effort derived index whose repair leg is a real sweep over the durable
 *  {@code publish/} pointer namespace, re-presenting every retained artifact exactly as the walk's {@code onRetained}
 *  would. */
class IndexObserverFixture implements PublicationHookFixture.Observer {

    @Override
    public String hook() {
        return "kit-index";
    }

    @Override
    public String providerClass() {
        return IndexObserver.class.getName();
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(IndexObserver.SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, IndexObserver.SPACE);
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        published.forEach(artifact -> converged.put(Keys.slug(artifact.path()), artifact.path()));
        return converged;
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        // The second route of the two-route derived-metadata contract, executed: a fresh instance is handed every
        // retained artifact read back out of the durable pointer namespace, so the surface is rebuilt from truth
        // rather than from anything the live events happened to deliver.
        PublicationObserver fresh = create();
        for (String path : Keys.published(store)) {
            String hash = Keys.read(store, "publish" + path).map(String::trim).orElse(null);
            fresh.onPublished(ArtifactDescriptor.at("kit", path).withBlob(hash, -1L), store);
        }
    }
}
