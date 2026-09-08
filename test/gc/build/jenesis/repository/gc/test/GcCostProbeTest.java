package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a collection's store operations actually go, by operation and by key family.
 *
 * <p><b>Why a probe rather than another counter.</b> The end-to-end canary reports what a collection costs in
 * total, which is the right number to hold a bound against and the wrong one to improve against: it says
 * {@code read.versioned} is seven tenths of the reads without saying which keys are being read. Reasoning from
 * the code instead cost a wrong answer on 2026-09-08 - a change made on the strength of reading the mark's
 * descriptor construction moved the measured figure by exactly nothing, because the reads were somewhere else
 * entirely. This names the somewhere.
 *
 * <p>It is a probe, so it asserts almost nothing: the collection has to be correct and the trace has to be
 * non-empty, and everything else is printed. A probe that asserted its own numbers would have to be edited every
 * time the thing it measures improves, which is how a measurement turns into a ratchet nobody reads.
 *
 * <p>The key family is the first two path segments, which is the grain the store is laid out in
 * ({@code blobs/<hash>}, {@code publish/<format>/...}, {@code gc/<pass>/refs/...}) and therefore the grain a
 * reduction is argued in. In process, over a real filesystem store, no container and no network.
 */
class GcCostProbeTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    /** Two sizes, because a collection's fixed cost per pass is large next to its per-object cost at any size a
     *  unit test can seed: a single figure would read as per-object when most of it is not. The slope between
     *  them is what a reduction has to move. */
    private static final int[] SIZES = {2_000, 20_000};

    /** The share left unreferenced, as an evicted version leaves it: a fifth. */
    private static final int COLLECTABLE_PERCENT = 20;

    /**
     * What {@code StoreWalkProvider} ships, so the figures are the ones a deployment pays.
     *
     * <p><b>This probe over-predicts against the end-to-end canary, and the gap is not yet explained.</b> Raising
     * this to 10,000 cut the mark's reference bookkeeping fivefold here - 1.22 store operations per blob to 0.24 -
     * and moved the containerised canary by thirty operations in half a million, with the compiled default
     * verified as the one the node ran. The probe's slope also predicts more writes to {@code gc/<pass>} at twenty
     * thousand blobs than that canary records in total, so its magnitudes do not carry to a real node. A flush
     * fires at segment completion as well as at the stride, so the segment count bounds the flush count from
     * below and the stride only binds once a segment holds more than a stride of keys - which is the likeliest
     * reason, and is unproven. Read this probe for the shape - which families exist and roughly how they grow -
     * and the canary for what anything costs.
     */
    private static final int CHECKPOINT = 1_000;

    private static final int SEGMENTS = 32;

    @Test
    void a_collection_reports_where_its_operations_go() throws IOException {
        Map<String, long[]> counts = new TreeMap<>();
        for (int size : SIZES) {
            measure(size, counts);
        }
        System.out.printf("[gc-probe] one collection (two passes) at %s blobs, a fifth collectable%n",
                Arrays.toString(SIZES));
        System.out.printf("    %-46s %9s %9s %9s%n", "operation and key family", "at " + SIZES[0],
                "at " + SIZES[1], "per blob");
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> each) ->
                        each.getValue()[1]).reversed())
                .forEach(each -> {
                    long small = each.getValue()[0], large = each.getValue()[1];
                    // The slope between the two sizes, which is the cost that grows with the store; the rest is
                    // the pass's fixed cost and does not.
                    double slope = (large - small) / (double) (SIZES[1] - SIZES[0]);
                    System.out.printf("    %-46s %9d %9d %9.3f%n", each.getKey(), small, large, slope);
                });
        assertThat(counts).as("the trace saw the collection").isNotEmpty();
    }

    /** One collection over a store of {@code size} blobs, folding each operation into {@code counts}. */
    private void measure(int size, Map<String, long[]> counts) throws IOException {
        int slot = size == SIZES[0] ? 0 : 1;
        Path at = Files.createDirectories(root.resolve("size-" + size));
        ArtifactStore backing = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? at.toString() : null);
        Set<String> collectable = seed(backing, size);

        FaultInjectingStore traced = FaultInjectingStore.wrap(backing).tracing((op, key) ->
                counts.computeIfAbsent(family(op, key), _ -> new long[SIZES.length])[slot]++);

        // Two passes: the collector condemns on one and deletes on the next, which is one collection.
        // The walk's OWN parameters, not the small ones the other tests here use to force interleavings: the
        // mark flushes its reference buffer before every cursor checkpoint, so the checkpoint interval decides how
        // many references share an object. At 5 rather than the shipped 1,000 this probe reported the flush cost
        // two hundred times too high, and a reduction was very nearly argued from it.
        MarkSweepGarbageCollector collector = new MarkSweepGarbageCollector(
                new StoreArtifactWalk(CHECKPOINT, SEGMENTS, Duration.ofMinutes(10), clock));
        var _ = collector.collect(traced, Known.known(List.of("publish")), clock.instant());
        var _ = collector.collect(traced, Known.known(List.of("publish")), clock.instant());

        for (String hash : collectable) {
            assertThat(backing.exists("blobs/" + hash))
                    .as("at %d blobs, %s was unreferenced and is reclaimed", size, hash).isFalse();
        }
    }

    /** The first two segments of the key, which is the grain the store is laid out in. */
    private static String family(Object op, String key) {
        if (key == null) {
            return op + " (no key)";
        }
        String[] segments = key.split("/");
        String family = segments.length < 2 ? segments[0] : segments[0] + "/" + segments[1];
        // A pass number and a hash are identities, not families: fold them so the shape shows rather than the data.
        return op + " " + family.replaceAll("[0-9a-f]{64}", "<hash>").replaceAll("^gc/[0-9]+", "gc/<pass>");
    }

    /** Blobs of distinct bytes, all linked; the collectable share then unpublished, as an eviction leaves it. */
    private Set<String> seed(ArtifactStore store, int blobs) throws IOException {
        Publication publication = new Publication(store);
        Set<String> collectable = new TreeSet<>();
        for (int index = 0; index < blobs; index++) {
            String path = "/raw/probe/artifact-" + index + ".bin";
            String hash = publication.storeBlob(new ByteArrayInputStream(
                    ("the distinct bytes of " + index).getBytes(StandardCharsets.UTF_8)));
            publication.link(path, hash);
            if (index % 100 < COLLECTABLE_PERCENT) {
                publication.unpublish(path);
                collectable.add(hash);
            }
        }
        return collectable;
    }
}
