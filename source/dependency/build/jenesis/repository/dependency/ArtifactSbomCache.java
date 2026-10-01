package build.jenesis.repository.dependency;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A bounded in-memory cache of the SBOM graph {@link ArtifactSbom} extracts from a blob, keyed by its content hash. A
 * blob is immutable, so what it embeds - or that it embeds nothing - is a permanent fact: a serving path resolving the
 * same blob repeatedly inflates it once, not per request. Negatives are cached too. Bounded by size, so it never grows
 * with the number of blobs served; a concurrent double load only recomputes the same immutable value.
 */
public final class ArtifactSbomCache {

    /** Extracts a blob's SBOM graph on a miss: the caller opens the blob and streams it through
     *  {@link ArtifactSbom#graph}. */
    @FunctionalInterface
    public interface Loader {
        Optional<DependencyGraph> load() throws IOException;
    }

    private final Cache<String, Optional<DependencyGraph>> cache;

    /** A cache holding at most {@code capacity} blobs' graphs. Maintenance runs on the caller's thread, so the bound
     *  holds as soon as a graph past it lands rather than when the common pool next runs. */
    public ArtifactSbomCache(int capacity) {
        this.cache = Caffeine.newBuilder().maximumSize(capacity).executor(Runnable::run).build();
    }

    /** The graph for a blob, loaded once however many callers ask at once; negatives are cached and an evicted entry
     *  simply loads again. */
    public Optional<DependencyGraph> graph(String blobHash, Loader loader) throws IOException {
        try {
            return cache.get(blobHash, _ -> {
                try {
                    return loader.load();
                } catch (IOException unloaded) {
                    throw new UncheckedIOException(unloaded);
                }
            });
        } catch (UncheckedIOException unloaded) {
            throw unloaded.getCause();
        }
    }
}
