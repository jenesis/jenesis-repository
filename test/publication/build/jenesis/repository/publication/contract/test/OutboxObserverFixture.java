package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** The {@link OutboxObserver} fixture: durable-after-enqueue, so its projection is the <em>drained</em> surface and
 *  its enqueued notes are a separate, independently readable state - which is exactly what the kit compares to tell
 *  this class from best-effort. */
final class OutboxObserverFixture implements PublicationHookFixture.Observer {

    @Override
    public String hook() {
        return "kit-outbox";
    }

    @Override
    public String providerClass() {
        return OutboxObserver.class.getName();
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(OutboxObserver.SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.DURABLE_AFTER_ENQUEUE;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, OutboxObserver.SENT);
    }

    @Override
    public Map<String, String> enqueued(ArtifactStore store) throws IOException {
        return Keys.rows(store, OutboxObserver.PENDING);
    }

    @Override
    public void drain(ArtifactStore store) throws IOException {
        OutboxObserver.drain(store);
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        published.forEach(artifact -> converged.put(Keys.slug(artifact.path()), artifact.path()));
        return converged;
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
