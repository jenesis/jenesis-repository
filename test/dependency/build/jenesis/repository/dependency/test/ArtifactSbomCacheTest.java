package build.jenesis.repository.dependency.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.dependency.ArtifactSbomCache;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blob-hash-keyed SBOM graph cache extracts a blob's embedded BOM once and serves it from memory on every later
 * request for the same content-addressed blob - so a repeated {@code /api/sbom} GET (including a fresh client with no
 * {@code If-None-Match}) never re-opens or re-decompresses the jar, and a deflate-bomb jar is inflated at most once per
 * node. A negative result is cached too, and the cache is bounded to its capacity - which of the entries the
 * library evicts past it is its policy, not this suite's claim.
 */
class ArtifactSbomCacheTest {

    private static DependencyGraph graph(String ref) {
        return new DependencyGraph(ref,
                List.of(new DependencyComponent(ref, null, ref, "1.0.0", null, null)), List.of());
    }

    @Test
    void a_second_lookup_for_the_same_blob_hits_the_cache_and_does_not_reload() throws IOException {
        ArtifactSbomCache cache = new ArtifactSbomCache(8);
        AtomicInteger loads = new AtomicInteger();
        DependencyGraph g = graph("a");

        Optional<DependencyGraph> first =
                cache.graph("hashA", () -> { loads.incrementAndGet(); return Optional.of(g); });
        Optional<DependencyGraph> second =
                cache.graph("hashA", () -> { loads.incrementAndGet(); return Optional.of(g); });

        assertThat(loads.get()).as("the loader runs once; the second GET is served from the cache").isEqualTo(1);
        assertThat(first).contains(g);
        assertThat(second.orElseThrow()).as("the cached graph instance is reused").isSameAs(g);
    }

    @Test
    void an_sbom_less_blob_is_negative_cached_and_not_re_decompressed() throws IOException {
        ArtifactSbomCache cache = new ArtifactSbomCache(8);
        AtomicInteger loads = new AtomicInteger();
        ArtifactSbomCache.Loader empty = () -> { loads.incrementAndGet(); return Optional.empty(); };

        assertThat(cache.graph("hashA", empty)).isEmpty();
        assertThat(cache.graph("hashA", empty)).isEmpty();

        assertThat(loads.get()).as("an empty result is cached too, so an SBOM-less blob is not re-decompressed")
                .isEqualTo(1);
    }

    @Test
    void distinct_blobs_are_loaded_and_cached_separately() throws IOException {
        ArtifactSbomCache cache = new ArtifactSbomCache(8);
        AtomicInteger loads = new AtomicInteger();

        cache.graph("hashA", () -> { loads.incrementAndGet(); return Optional.of(graph("a")); });
        cache.graph("hashB", () -> { loads.incrementAndGet(); return Optional.of(graph("b")); });

        assertThat(loads.get()).as("two distinct blobs each load once").isEqualTo(2);
    }

    @Test
    void the_cache_is_bounded_to_its_capacity() throws IOException {
        ArtifactSbomCache cache = new ArtifactSbomCache(2);
        AtomicInteger loads = new AtomicInteger();
        ArtifactSbomCache.Loader load = () -> { loads.incrementAndGet(); return Optional.of(graph("g")); };

        cache.graph("A", load);
        cache.graph("B", load);
        cache.graph("C", load);                 // three blobs through a bound of two
        assertThat(loads.get()).as("each blob loaded once").isEqualTo(3);

        cache.graph("A", load);
        cache.graph("B", load);
        cache.graph("C", load);
        assertThat(loads.get())
                .as("asked for all three again, at least one had to reload: the bound held, whichever the library kept")
                .isGreaterThan(3);
        assertThat(loads.get())
                .as("and at least one was still cached: the bound is two, not zero")
                .isLessThan(6);
    }
}
