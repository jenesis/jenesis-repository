package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.index.IndexDescriptor;
import build.jenesis.repository.index.PublishedIndex;
import build.jenesis.repository.index.PublishedIndexTask;
import build.jenesis.repository.index.SeekableIndex;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published index's write-path observer: every accepted publish marks its path in the index's dirty feed, which the
 * scheduled pass appends to the chain. The projection is every path the index covers - marked in the feed or already
 * in the chain - since a path moves from the one to the other without the index having changed what it will serve.
 * The repair is the walk's {@code index-rebase}: the chain rebuilt from the {@code publish/} pointers, which is how a
 * lost mark comes back.
 */
final class IndexPublicationObserverFixture implements PublicationHookFixture.Observer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String hook() {
        return "index-publication";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.index.IndexPublicationObserver";
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
        Map<String, String> indexed = new TreeMap<>();
        for (DirtyIndexFeed.Entry entry : new DirtyIndexFeed(store, PublishedIndexKeys.PREFIX).pending()) {
            if (!entry.removed()) {
                indexed.put(entry.coordinate(), "indexed");
            }
        }
        PublishedIndex index = new PublishedIndex(store);
        Optional<IndexDescriptor> descriptor = index.descriptor();
        if (descriptor.isPresent()) {
            for (IndexDescriptor.Chunk chunk : descriptor.get().chain()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                index.streamChunk(chunk.id(), bytes);
                for (byte[] line : SeekableIndex.records(bytes.toByteArray())) {
                    indexed.put(JSON.readTree(line).path("path").asString(), "indexed");
                }
            }
        }
        return indexed;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> indexed = new TreeMap<>();
        published.forEach(artifact -> indexed.put(artifact.path(), "indexed"));
        return indexed;
    }

    /** The walk's index-rebase: the whole chain rebuilt from the durable pointers. */
    @Override
    public void repair(ArtifactStore store) throws IOException {
        new PublishedIndexTask(Duration.ZERO, 1L << 20).rebase(store, Instant.now());
    }
}
