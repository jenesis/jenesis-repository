package build.jenesis.repository.dependency;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A bounded in-memory cache of the SBOM graph {@link ArtifactSbom} extracts from a stored blob, keyed by the
 * blob's content hash. A blob is content-addressed and immutable, so "blob {@code <hash>} embeds graph G" - or embeds
 * none - is a permanent fact: caching it lets a serving path that resolves the same blob on repeated GETs skip
 * re-opening and re-decompressing it, so a max-ratio deflate-bomb jar is inflated at most once rather than on every
 * request (the per-GET decompression {@link ArtifactSbom} bounds but does not eliminate). A negative result (a non-jar,
 * SBOM-less or unreadable blob) is cached too, so an SBOM-less blob is not re-decompressed on the next request either -
 * the same immutable-blob reasoning the dependents sweep's negative cache uses.
 *
 * <p>The cache is bounded to a fixed capacity by access-ordered LRU eviction (the idiom the cache server's project
 * cache uses), so it never grows without limit however many distinct blobs are served. A concurrent double-load only
 * ever recomputes an identical immutable value for the same content-addressed key, so the get-then-load-then-put is
 * left unsynchronised beyond the backing map's own guard.
 */
public final class ArtifactSbomCache {

    /** Extracts a blob's embedded SBOM graph on a cache miss - the caller opens the blob and streams it through
     *  {@link ArtifactSbom#graph}, paying the (bounded) decompression exactly once per distinct blob. */
    @FunctionalInterface
    public interface Loader {
        Optional<DependencyGraph> load() throws IOException;
    }

    private final Cache<String, Optional<DependencyGraph>> cache;

    /** A cache holding at most {@code capacity} distinct blobs' graphs. Its maintenance runs on the caller's
     *  thread, so the bound holds the moment a graph past it lands rather than after the common pool's next
     *  turn - measured: three graphs through a bound of two were all still served until that turn came. */
    public ArtifactSbomCache(int capacity) {
        this.cache = Caffeine.newBuilder().maximumSize(capacity).executor(Runnable::run).build();
    }

    /** The graph for a blob, loaded once per blob however many callers ask at once; a negative answer is cached
     *  too, and an eviction past the bound simply loads again. */
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
