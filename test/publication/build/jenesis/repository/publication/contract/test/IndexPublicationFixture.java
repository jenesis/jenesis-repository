package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.index.PublishedIndex;
import build.jenesis.repository.index.PublishedIndexTask;
import build.jenesis.repository.index.SeekableIndex;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The published index's write-path mark: a publish marks its request path in the index's dirty feed, and the
 * scheduled pass appends exactly the marked paths. The projection is "the path is marked or indexed", because
 * the two routes to convergence differ in which: the observer leaves a mark the next pass consumes, and the repair -
 * the rebase a walk carries - indexes the path directly from its pointer and compacts the feed. Either way the path
 * is on its way into, or in, the served chain, which is the fact a lost call must not lose.
 */
final class IndexPublicationFixture implements PublicationHookFixture.Observer {

    static final String MARKED_OR_INDEXED = "marked-or-indexed";

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
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> converged = new TreeMap<>();
        for (ArtifactDescriptor artifact : published) {
            converged.put(artifact.path(), MARKED_OR_INDEXED);
        }
        return converged;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> projection = new TreeMap<>();
        for (DirtyIndexFeed.Entry entry : new DirtyIndexFeed(store, PublishedIndexKeys.PREFIX).pending()) {
            projection.put(entry.coordinate(), MARKED_OR_INDEXED);
        }
        PublishedIndex index = new PublishedIndex(store);
        index.descriptor().ifPresent(descriptor -> {
            for (var chunk : descriptor.chain()) {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    index.streamChunk(chunk.id(), out);
                    for (byte[] line : SeekableIndex.records(out.toByteArray())) {
                        Matcher matcher = PATH.matcher(new String(line, StandardCharsets.UTF_8));
                        if (matcher.find()) {
                            projection.put(matcher.group(1), MARKED_OR_INDEXED);
                        }
                    }
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }
        });
        return projection;
    }

    /** The rebase a walk of the store carries: the chain re-derived from every pointer, the feed compacted. */
    @Override
    public void repair(ArtifactStore store) throws IOException {
        new PublishedIndexTask(Duration.ofDays(1), 8L * 1024 * 1024).rebase(store, Instant.now());
    }

    private static final Pattern PATH = Pattern.compile("\"path\":\"([^\"]*)\"");
}
