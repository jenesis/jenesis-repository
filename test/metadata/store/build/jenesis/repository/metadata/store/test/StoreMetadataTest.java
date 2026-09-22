package build.jenesis.repository.metadata.store.test;

import module java.base;
import module tools.jackson.databind;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;
import build.jenesis.repository.metadata.store.MetadataMetrics;
import build.jenesis.repository.metadata.store.StoreMetadata;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store-backed consolidated metadata store over a real filesystem artifact store: a section round-trips, a
 * multi-section batch mutate is a single CAS, disjoint-section writers converge through a CAS retry (deterministically
 * and under real concurrency), an unrecognised section survives a foreign writer's mutate untouched (the 
 * carry property at the section level), a newer-format document is refused rather than downgrade-rewritten, and the
 * provider is ServiceLoader-discovered.
 */
class StoreMetadataTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final String ECO = "Maven";
    private static final String COORD = "org.example:lib";
    private static final String VERSION = "1.0";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static JsonNode data(String field, Object value) {
        ObjectNode node = JSON.createObjectNode();
        node.putPOJO(field, value);
        return node;
    }

    private static SectionMutation set(Section section) {
        return current -> section;
    }

    @Test
    void a_section_round_trips_through_a_single_mutate() throws IOException {
        MetadataStore metadata = new StoreMetadata(store);
        metadata.mutate(ECO, COORD, VERSION, "published",
                set(Section.derived("published", 1, NOW, Signal.NEUTRAL, data("prerelease", false))));

        Section read = metadata.section(ECO, COORD, VERSION, "published").orElseThrow();
        assertThat(read.tag()).isEqualTo("published");
        assertThat(read.schema()).isEqualTo(1);
        assertThat(read.updated()).isEqualTo(NOW);
        assertThat(read.state()).isEqualTo(State.DERIVED);
        assertThat(read.payload().orElseThrow().path("prerelease").asBoolean()).isFalse();
    }

    @Test
    void a_multi_section_batch_mutate_is_one_cas() throws IOException {
        CountingStore counting = new CountingStore(store);
        MetadataMetrics metrics = new MetadataMetrics();
        MetadataStore metadata = new StoreMetadata(counting, metrics);

        SequencedMap<String, SectionMutation> batch = new LinkedHashMap<>();
        batch.put("licenses", set(Section.derived("licenses", 1, NOW, Signal.NEUTRAL, data("declared", "Apache-2.0"))));
        batch.put("published", set(Section.derived("published", 1, NOW, Signal.NEUTRAL, data("prerelease", false))));
        batch.put("findings", set(Section.derived("findings", 1, NOW, Signal.of(Severity.HIGH), data("count", 2))));
        metadata.mutate(ECO, COORD, VERSION, batch);

        assertThat(counting.writes()).as("three sections commit in one CAS write").isEqualTo(1);
        assertThat(metrics.mutations()).isEqualTo(1);
        assertThat(metrics.retries()).as("no conflict, no retry").isZero();
        MetadataDocument document = metadata.read(ECO, COORD, VERSION).orElseThrow();
        assertThat(document.tags()).containsExactlyInAnyOrder("licenses", "published", "findings");
    }

    @Test
    void disjoint_section_writers_converge_through_a_cas_retry() throws IOException {
        // A store that, on the first writeVersioned of the doc, slips a *different* section in underneath - so the
        // caller's CAS token is now stale and the mutate must re-read and re-apply. Convergence means both the
        // injected section AND the caller's land: the section-level carry re-merges instead of clobbering.
        Section injected = Section.derived("licenses", 1, NOW, Signal.NEUTRAL, data("declared", "MIT"));
        RaceInjectingStore racing = new RaceInjectingStore(store, MetadataKey.version(ECO, COORD, VERSION), injected);
        MetadataMetrics metrics = new MetadataMetrics();
        MetadataStore metadata = new StoreMetadata(racing, metrics);

        metadata.mutate(ECO, COORD, VERSION, "published",
                set(Section.derived("published", 1, NOW, Signal.NEUTRAL, data("prerelease", false))));

        assertThat(metrics.retries()).as("the stale token forced exactly one retry").isEqualTo(1);
        MetadataDocument document = metadata.read(ECO, COORD, VERSION).orElseThrow();
        assertThat(document.tags())
                .as("the disjoint injected section and the caller's both converged")
                .containsExactlyInAnyOrder("licenses", "published");
        assertThat(document.section("licenses").orElseThrow().payload().orElseThrow().path("declared").asString())
                .isEqualTo("MIT");
    }

    @Test
    void concurrent_disjoint_writers_all_land() throws Exception {
        // Every writer hammers ONE key at once, so a bounded 5-retry mutate can legitimately lose the race under
        // worst-case contention - exactly what a real sweep treats as retryable. The property under test is
        // convergence: with the caller re-trying a transient loss, every disjoint section must survive, none
        // clobbered. (The single-retry convergence path is proven deterministically above.)
        int writers = 8;
        MetadataStore metadata = new StoreMetadata(store);
        CyclicBarrier barrier = new CyclicBarrier(writers);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
            String tag = "writer" + index;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    for (int attempt = 0; ; attempt++) {
                        try {
                            metadata.mutate(ECO, COORD, VERSION, tag,
                                    set(Section.derived(tag, 1, NOW, Signal.NEUTRAL, data("who", tag))));
                            break;
                        } catch (IOException lostRace) {
                            if (attempt >= 50) {
                                throw lostRace;
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        MetadataDocument document = metadata.read(ECO, COORD, VERSION).orElseThrow();
        Set<String> expected = new TreeSet<>();
        for (int index = 0; index < writers; index++) {
            expected.add("writer" + index);
        }
        assertThat(document.tags()).as("every concurrent disjoint-section writer's section survived")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void an_unknown_section_survives_a_foreign_writers_mutate() throws IOException {
        MetadataStore metadata = new StoreMetadata(store);
        // A newer/custom module writes a section this node does not recognise, with its own schema and payload.
        ObjectNode custom = JSON.createObjectNode();
        custom.put("finding", "GHSA-xyz");
        custom.put("weird", 42);
        metadata.mutate(ECO, COORD, VERSION, "com.acme.scanner",
                set(Section.derived("com.acme.scanner", 7, NOW, Signal.of(Severity.CRITICAL), custom)));

        // An "older" node - a mutate that names only a different, known tag - rewrites the document.
        metadata.mutate(ECO, COORD, VERSION, "licenses",
                set(Section.derived("licenses", 1, NOW, Signal.NEUTRAL, data("declared", "Apache-2.0"))));

        MetadataDocument document = metadata.read(ECO, COORD, VERSION).orElseThrow();
        Section survived = document.section("com.acme.scanner").orElseThrow();
        assertThat(survived.schema()).as("the unrecognised section's own version rides through").isEqualTo(7);
        assertThat(survived.signal().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(survived.payload().orElseThrow().path("finding").asString()).isEqualTo("GHSA-xyz");
        assertThat(survived.payload().orElseThrow().path("weird").asInt()).isEqualTo(42);
    }

    @Test
    void a_newer_format_document_is_refused_not_downgrade_rewritten() throws IOException {
        String key = MetadataKey.version(ECO, COORD, VERSION);
        byte[] newer = "{\"format\":2,\"sections\":{\"future\":{\"schema\":1}}}".getBytes(StandardCharsets.UTF_8);
        store.writeVersioned(key, newer, null);
        MetadataStore metadata = new StoreMetadata(store);

        assertThatThrownBy(() -> metadata.mutate(ECO, COORD, VERSION, "published",
                set(Section.derived("published", 1, NOW, Signal.NEUTRAL, data("prerelease", false)))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.readVersioned(key).orElseThrow().content())
                .as("the newer-format bytes are left exactly as written").isEqualTo(newer);
    }

    @Test
    void a_coordinate_scoped_section_round_trips_and_does_not_alias_a_version_document() throws IOException {
        MetadataStore metadata = new StoreMetadata(store);
        // The per-coordinate (@coordinate) document carries the version-independent facts; a same-named version
        // document must live at a distinct key, so writing one never overwrites the other (§5).
        metadata.mutateCoordinate(ECO, COORD, "health",
                set(Section.derived("health", 1, NOW, Signal.NEUTRAL, data("overall", 4.2))));
        metadata.mutate(ECO, COORD, VERSION, "published",
                set(Section.derived("published", 1, NOW, Signal.NEUTRAL, data("prerelease", false))));

        Section coordinate = metadata.coordinateSection(ECO, COORD, "health").orElseThrow();
        assertThat(coordinate.payload().orElseThrow().path("overall").asDouble()).isEqualTo(4.2);
        assertThat(metadata.readCoordinate(ECO, COORD).orElseThrow().tags())
                .as("the coordinate document holds only its own section").containsExactly("health");
        assertThat(metadata.read(ECO, COORD, VERSION).orElseThrow().tags())
                .as("the version document is a distinct object, unaffected by the coordinate write")
                .containsExactly("published");
        assertThat(store.readVersioned(MetadataKey.coordinate(ECO, COORD)))
                .as("the coordinate document lives at the reserved @coordinate key").isPresent();
    }

    @Test
    void the_provider_is_discovered() {
        MetadataProvider provider = MetadataProvider.installed().orElseThrow();
        assertThat(provider.over(store)).isInstanceOf(StoreMetadata.class);
    }

    /** A store wrapper that delegates everything and counts compare-and-set writes. */
    private static class CountingStore extends DelegatingStore {

        private final AtomicInteger writes = new AtomicInteger();

        CountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        int writes() {
            return writes.get();
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            writes.incrementAndGet();
            return super.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** A store wrapper that, on the first CAS write to a target key, first commits a competing section underneath -
     *  so the caller's token goes stale and its write conflicts exactly once, exercising the re-read-and-retry
     *  convergence. */
    private static final class RaceInjectingStore extends DelegatingStore {

        private final String targetKey;
        private final Section injected;
        private boolean raced;

        RaceInjectingStore(ArtifactStore delegate, String targetKey, Section injected) {
            super(delegate);
            this.targetKey = targetKey;
            this.injected = injected;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (!raced && key.equals(targetKey)) {
                raced = true;
                Optional<Versioned> current = super.readVersioned(key);
                MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                        .orElseGet(MetadataDocument::empty);
                SequencedMap<String, SectionMutation> injection = new LinkedHashMap<>();
                injection.put(injected.tag(), c -> injected);
                super.writeVersioned(key, document.mutate(injection).serialize(),
                        current.map(Versioned::token).orElse(null));
                // The caller's original expected-token is now stale, so this write must report the conflict.
            }
            return super.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** Delegates every {@link ArtifactStore} method to a backing store, so a wrapper overrides only what it tests. */
    private abstract static class DelegatingStore implements ArtifactStore {

        private final ArtifactStore delegate;

        DelegatingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object identity() {
            return delegate.identity();   // a forwarding base forwards its subspace, so every subclass inherits it
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
