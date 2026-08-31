package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

class StoredListingTest {

    private static final StoredListing.Codec LINES = StoredListing.Codec.delimited("\n",
            line -> line.substring(0, line.indexOf(' ')));

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null).scope("acme");
    }

    @Test
    void a_streaming_generator_writes_the_same_document_as_a_materialising_one() throws IOException {
        // The equivalence that makes converting a generator safe. Every repository-wide listing has to move from
        // returning its map to emitting into a sink, and the only thing that makes those changes reviewable one at
        // a time is that the two shapes are indistinguishable in the store.
        SortedMap<String, byte[]> content = entries("a 1", "b 2", "c 3");

        StoredListing.read(store, lines("collected", () -> content));
        StoredListing.read(store, StoredListing.Spec.of("streamed", LINES, sink -> {
            for (Map.Entry<String, byte[]> entry : content.entrySet()) {
                sink.accept(entry.getKey(), entry.getValue());
            }
        }));

        assertThat(body("streamed")).isEqualTo(body("collected"));
    }

    @Test
    void a_streaming_generator_never_has_the_whole_listing_in_hand() throws IOException {
        // The point of the seam, asserted on the generator's own side: entries leave it one at a time, so what it
        // holds does not grow with the repository. A generator that built a map and then emitted it would fail
        // this - the count would already be at its maximum when the first entry arrived.
        int total = 500;
        List<Integer> liveWhenEmitted = new ArrayList<>();
        AtomicInteger live = new AtomicInteger();

        StoredListing.read(store, StoredListing.Spec.of("streamed", LINES, sink -> {
            for (int each = 0; each < total; each++) {
                live.incrementAndGet();
                sink.accept(String.format("%04d", each), (each + " x").getBytes(StandardCharsets.UTF_8));
                liveWhenEmitted.add(live.getAndSet(0));   // handed over and released
            }
        }));

        assertThat(liveWhenEmitted).hasSize(total).containsOnly(1);
    }

    @Test
    void the_materialising_adapter_preserves_order_and_content() throws IOException {
        // The adapter is what the unconverted generators run through, so it has to be exactly transparent.
        SortedMap<String, byte[]> content = entries("b 2", "a 1", "c 3");
        List<String> seen = new ArrayList<>();

        StoredListing.Generator.materialising(() -> content).generate((id, entry) -> seen.add(id));

        assertThat(seen).containsExactly("a", "b", "c");
    }

    private static SortedMap<String, byte[]> entries(String... lines) {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String line : lines) {
            entries.put(line.substring(0, line.indexOf(' ')), line.getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    private static StoredListing.Spec lines(String listing, StoredListing.Generator.Materialising generator) {
        return StoredListing.Spec.materialising(listing, LINES, generator);
    }

    private static StoredListing.Spec lines(String listing) {
        return lines(listing, TreeMap::new);
    }

    private String body(String listing) throws IOException {
        Optional<StoredListing.Document> document = StoredListing.read(store, lines(listing));
        return document.map(d -> new String(d.body(), StandardCharsets.UTF_8)).orElse(null);
    }

    @Test
    void materialises_on_first_read_and_serves_the_document_afterwards() throws IOException {
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger derived = new AtomicInteger();
        StoredListing.Generator.Materialising generator = () -> {
            generated.incrementAndGet();
            return entries("b 2", "a 1");
        };
        StoredListing.Spec spec = lines("go/example/list", generator).deriving(d -> derived.incrementAndGet());
        try (StoredListing.Served served = StoredListing.open(store, spec)
                .orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            served.copyTo(out);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("a 1\nb 2\n");
            assertThat(served.header().size()).isEqualTo(8);
            assertThat(served.header().sha256()).hasSize(64);
        }
        try (StoredListing.Served served = StoredListing.open(store, spec).orElseThrow()) {
            assertThat(served.header().size()).isEqualTo(8);
        }
        assertThat(generated).hasValue(1);
        assertThat(store.exists("listing/go/example/list")).isTrue();
        assertThat(derived).as("the first materialisation derives, like every write").hasValue(1);
    }

    @Test
    void an_empty_generation_is_stored_as_an_empty_document() throws IOException {
        try (StoredListing.Served served = StoredListing.open(store, lines("empty")).orElseThrow()) {
            assertThat(served.header().size()).isZero();
        }
        assertThat(StoredListing.present(store, "empty")).isTrue();
    }

    @Test
    void updates_are_incremental_over_the_stored_document() throws IOException {
        AtomicInteger generated = new AtomicInteger();
        StoredListing.Generator.Materialising generator = () -> {
            generated.incrementAndGet();
            return entries("a 1");
        };
        assertThat(StoredListing.put(store, lines("l", generator), "c", "c 3".getBytes())).isTrue();
        assertThat(StoredListing.put(store, lines("l", generator), "b", "b 2".getBytes())).isTrue();
        assertThat(StoredListing.put(store, lines("l", generator), "a", "a 9".getBytes())).isTrue();
        assertThat(StoredListing.remove(store, lines("l", generator), "c")).isTrue();
        assertThat(body("l")).isEqualTo("a 9\nb 2\n");
        assertThat(generated).as("generated once, on the first write, never again").hasValue(1);
    }

    @Test
    void the_sequence_grows_with_every_write_and_the_derivation_sees_each_document() throws IOException {
        List<Long> seen = new ArrayList<>();
        StoredListing.Derivation derivation = document -> seen.add(document.header().seq());
        StoredListing.put(store, lines("s").deriving(derivation), "a", "a 1".getBytes());
        StoredListing.put(store, lines("s").deriving(derivation), "b", "b 2".getBytes());
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).isGreaterThan(seen.get(0));
        assertThat(StoredListing.header(store, "s").orElseThrow().seq()).isEqualTo(seen.get(1));
    }

    @Test
    void concurrent_writers_all_land_and_coalesce() throws Exception {
        int writers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String id = "w" + String.format("%02d", i);
                outcomes.add(pool.submit(() -> {
                    go.await();
                    return StoredListing.put(store, lines("busy"), id, (id + " x").getBytes());
                }));
            }
            go.countDown();
            for (Future<Boolean> outcome : outcomes) {
                assertThat(outcome.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        String body = body("busy");
        assertThat(body.lines()).hasSize(writers);
        for (int i = 0; i < writers; i++) {
            assertThat(body).contains("w" + String.format("%02d", i) + " x\n");
        }
    }

    @Test
    void a_writer_that_loses_the_compare_and_set_retries_on_the_fresh_document() throws IOException {
        StoredListing.put(store, lines("raced"), "a", "a 1".getBytes());
        // A second node's write between this node's read and write: simulated by a store whose identity differs
        // (the wrapper's own, so no lane is shared) writing straight through the same root.
        ArtifactStore other = FaultInjectingStore.wrap(store);
        assertThat(other.identity()).isNotEqualTo(store.identity());
        StoredListing.Generator.Materialising racing = () -> {
            StoredListing.put(other, lines("raced"), "z", "z 26".getBytes());
            return new TreeMap<>();
        };
        // Forget, so this node's next write materialises through `racing`, which sneaks a write in first: the
        // atomic create then conflicts, and the retry reads z back before adding b.
        StoredListing.forget(store, "raced");
        assertThat(StoredListing.put(store, lines("raced", racing), "b", "b 2".getBytes())).isTrue();
        assertThat(body("raced")).isEqualTo("b 2\nz 26\n");
    }

    @Test
    void derived_documents_are_ordered_by_sequence() throws IOException {
        assertThat(StoredListing.derive(store, "d.gz", 10L, "ten".getBytes())).isTrue();
        assertThat(StoredListing.derive(store, "d.gz", 5L, "five".getBytes())).as("older than stored").isFalse();
        assertThat(StoredListing.derive(store, "d.gz", 10L, "ten again".getBytes())).as("same sequence").isFalse();
        assertThat(StoredListing.derive(store, "d.gz", 11L, "eleven".getBytes())).isTrue();
        try (StoredListing.Served served = StoredListing.openDerived(store, "d.gz").orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            served.copyTo(out);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("eleven");
            assertThat(served.header().seq()).isEqualTo(11L);
        }
        assertThat(StoredListing.openDerived(store, "never")).isEmpty();
    }

    @Test
    void forget_makes_the_next_read_regenerate_and_rebuild_replaces() throws IOException {
        StoredListing.put(store, lines("r"), "stale", "stale 0".getBytes());
        StoredListing.forget(store, "r");
        assertThat(StoredListing.present(store, "r")).isFalse();
        assertThat(body("r")).isEmpty();
        StoredListing.put(store, lines("r"), "stale", "stale 0".getBytes());
        StoredListing.Document rebuilt = StoredListing.rebuild(store, lines("r", () -> entries("fresh 1")));
        assertThat(new String(rebuilt.body(), StandardCharsets.UTF_8)).isEqualTo("fresh 1\n");
        assertThat(body("r")).isEqualTo("fresh 1\n");
    }

    @Test
    void a_delimited_codec_round_trips_stanzas() {
        StoredListing.Codec stanzas = StoredListing.Codec.delimited("\n\n", stanza -> stanza.lines()
                .filter(line -> line.startsWith("Package: ")).findFirst().orElseThrow().substring(9));
        SortedMap<String, byte[]> entries = entries("zlib 1", "acl 2");
        entries.clear();
        entries.put("zlib", "Package: zlib\nVersion: 1".getBytes());
        entries.put("acl", "Package: acl\nVersion: 2".getBytes());
        byte[] joined = stanzas.join(entries);
        assertThat(new String(joined, StandardCharsets.UTF_8))
                .isEqualTo("Package: acl\nVersion: 2\n\nPackage: zlib\nVersion: 1\n\n");
        SortedMap<String, byte[]> split = stanzas.split(joined);
        assertThat(split.keySet()).containsExactly("acl", "zlib");
        assertThat(new String(split.get("zlib"), StandardCharsets.UTF_8)).isEqualTo("Package: zlib\nVersion: 1");
        assertThat(stanzas.split(new byte[0])).isEmpty();
    }

    @Test
    void a_placeholder_is_substituted_on_the_way_out() throws IOException {
        StoredListing.Spec spec = lines("sub", () -> entries("a {{base}}/a.zip", "b x{{bas{{base}}y", "c {{base}}"));
        try (StoredListing.Served served = StoredListing.open(store, spec).orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            served.copyTo(out, "{{base}}", "https://host/r");
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .isEqualTo("a https://host/r/a.zip\nb x{{bashttps://host/ry\nc https://host/r\n");
        }
        try (StoredListing.Served served = StoredListing.open(store, lines("trail", () -> entries("t {{ba")))
                .orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            served.copyTo(out, "{{base}}", "X");
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("t {{ba\n");
        }
    }

    /** A deferred derivation runs off the caller's thread, and {@code settle} waits for what was queued before it -
     *  the guarantee a stopping node, or a test about to tear its store down, relies on. */
    @Test
    void settle_waits_for_the_deferred_derivations_queued_before_it() {
        AtomicInteger ran = new AtomicInteger();
        Thread caller = Thread.currentThread();
        AtomicBoolean onCaller = new AtomicBoolean();
        StoredListing.later("test/twin", () -> {
            onCaller.set(Thread.currentThread() == caller);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.incrementAndGet();
        });
        StoredListing.settle();
        assertThat(ran).as("settle returns once the queued derivation has run").hasValue(1);
        assertThat(onCaller).as("the derivation ran off the queuing thread").isFalse();
    }

    /** A put rewrites the one document, so its cost grows with the document and with nothing else: ten times the
     *  entries may cost ten times the put, never a hundred - the guard against a write that re-reads or re-scans
     *  per entry. Measured over a Packages-shaped listing of 300-byte stanzas. */
    @Test
    void a_put_costs_the_document_it_rewrites_and_not_more() throws IOException {
        StoredListing.Spec spec = StoredListing.Spec.materialising("scale/Packages",
                StoredListing.Codec.delimited("\n\n", stanza -> stanza.substring(9, stanza.indexOf('\n'))),
                TreeMap::new);
        String filler = "x".repeat(260);
        double perPutAt1k = 0, perPutAt10k = 0;
        long window = System.nanoTime();
        for (int i = 1; i <= 10_000; i++) {
            StoredListing.put(store, spec, Integer.toString(i),
                    ("Package: " + i + "\nVersion: 1." + i + "\nDescription: " + filler).getBytes(StandardCharsets.UTF_8));
            if (i == 1_000) {
                perPutAt1k = (System.nanoTime() - window) / 1e6 / 1_000;
                window = System.nanoTime();
            } else if (i == 10_000) {
                perPutAt10k = (System.nanoTime() - window) / 1e6 / 9_000;
            }
        }
        System.out.printf("[scale] %.2f ms per put at 1k entries, %.2f ms at 10k%n", perPutAt1k, perPutAt10k);
        assertThat(perPutAt10k / Math.max(perPutAt1k, 0.5))
                .as("a put at 10k entries against one at 1k: linear in the document, not quadratic")
                .isLessThan(25);
    }

    @Test
    void the_store_key_is_under_the_listing_root() {
        assertThat(StoredListing.key("debian/main/Packages")).isEqualTo("listing/debian/main/Packages");
    }
}
