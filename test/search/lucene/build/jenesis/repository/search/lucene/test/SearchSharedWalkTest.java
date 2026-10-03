package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.WalkSegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The search-index sweep's ride on the shared artifact walk: with a walk installed the release enumeration
 * is the task's own resumable {@code walks/search} pass instead of a private listing, while the one-zip Lucene
 * snapshot stays a single-writer, <em>restart-on-crash</em> rebuild - the recorded deliberate exception to the
 * walk's mid-pass resume. Verified here: the walk-riding sweep serves exactly what the streaming sweep serves
 * (documents and licence filter terms, across a multi-segment pass); a crash mid-pass commits nothing and the next sweep
 * re-enumerates from the start - the pre-crash releases are provably re-read, the restart discipline - and serves
 * the complete index; a second in-process walk instance (the stand-in for another VM sharing the store) takes a
 * dead worker's pass over but never commits the partial view it joined into, rebuilding fresh instead; a live
 * foreign claim defers the commit entirely (refuse, don't steal); and a pass another live worker shared in is
 * discarded by the single-holder guard, so a split enumeration can never surface as a silently-partial index. Walk
 * settings pinned small as in the other shared-walk suites: a one-second claim TTL, and one segment where the crash
 * position must be deterministic.
 */
class SearchSharedWalkTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");
    /** Six coordinate releases and the one path-addressed upload beside them. */
    private static final int RELEASES = 7;

    /** How many times a wait below re-drives its pass before giving up. The bound is attempts, never a reading of
     *  the wall clock: a loaded machine must make the wait longer, not weaker. A clock-bounded wait that expires
     *  hands the cells an <em>empty</em> recording, and "no blob before the cursor was re-opened" is true of a pass
     *  that never ran at all - the resume-versus-restart claim would then pass saying nothing. */
    private static final int PASSES = 300;

    @TempDir
    Path root;

    @Test
    void the_walk_riding_sweep_serves_exactly_what_the_streaming_sweep_serves() throws IOException {
        ArtifactStore store = store();
        seedReleases(store);
        new LicenseInventory(store).record("maven", "org.example:lib1", "1.0",
                List.of(new LicenseInventory.Declared("MIT License", null)));
        sweep(store, null);                                            // the streaming, walk-less build: generation 1
        List<String> streamed = hits(query(store), "");
        List<String> licensed = hits(query(store), "license:MIT");
        assertThat(streamed).hasSize(RELEASES);
        assertThat(streamed).as("the streaming rebuild indexes the path-addressed upload, which has no coordinate "
                        + "row to be enumerated from and would otherwise be findable only by walking the store")
                .contains(RAW_PATH);

        // Four segments over three ecosystems: the pass is genuinely cut, and the one worker claims range by range.
        sweep(store, walk(settings(4)));                               // the walk-riding build: generation 2

        assertThat(hits(query(store), "")).as("the walk-riding index serves document for document the same")
                .containsExactlyElementsOf(streamed);
        assertThat(hits(query(store), "license:MIT")).as("and the same licence filter terms").isEqualTo(licensed)
                .containsExactly("org.example:lib1:1.0");
        assertThat(store.readVersioned("walks/search/manifest"))
                .as("the enumeration rode the task's own shared-walk pass").isPresent();
        assertThat(store.exists("index/search/2.manifest")).isTrue();
    }

    @Test
    void a_mid_pass_crash_commits_nothing_and_the_next_sweep_restarts_and_serves_the_complete_index()
            throws IOException {
        ArtifactStore store = store();
        seedReleases(store);
        ArtifactWalk walk = walk(settings(1));
        // By the third release's record read two releases are indexed in the doomed in-memory accumulation and the
        // walk's cursor is committed past them (checkpoint stride 1) - exactly the state a resume would build a
        // truncated index from.
        CrashingStore crashing = new CrashingStore(store, 3);

        assertThatThrownBy(() -> sweep(crashing, walk))
                .as("the third release's record read crashes the worker").isInstanceOf(IllegalStateException.class);
        assertThat(store.readVersioned("index/search/current"))
                .as("a crashed build commits no manifest - never a truncated index").isEmpty();
        List<String> preCrash = crashing.read.subList(0, 2);

        RecordingStore recording = new RecordingStore(store);
        awaitSweep(recording, walk);

        for (String key : preCrash) {
            assertThat(recording.read.getOrDefault(key, 0))
                    .as("a release before the committed cursor is read again - a restart, not a resume (the " +
                            "recorded exception: the one-zip snapshot cannot adopt a dead worker's memory)")
                    .isPositive();
        }
        assertThat(hits(query(store), "")).as("the committed index is whole").hasSize(RELEASES);
    }

    @Test
    void a_second_walk_instance_takes_a_dead_workers_pass_over_but_never_commits_the_partial_view()
            throws IOException {
        ArtifactStore store = store();
        seedReleases(store);
        // Two walk instances over one store: each resolve carries its own node identity, so this is the in-process
        // stand-in for two VMs sharing the store - pass, claims and cursors travel through the store.
        ArtifactWalk nodeA = walk(settings(1));
        ArtifactWalk nodeB = walk(settings(1));
        CrashingStore crashing = new CrashingStore(store, 3);

        assertThatThrownBy(() -> sweep(crashing, nodeA)).as("node A dies mid-pass")
                .isInstanceOf(IllegalStateException.class);

        awaitSweep(store, nodeB);

        // Node B first drove node A's leftover pass to completion - a tail that misses A's pre-cursor releases -
        // and the freshness guard made it discard that view and rebuild a fresh generation with everything.
        assertThat(hits(query(store), "")).as("node B committed the whole repository, not the adopted tail")
                .hasSize(RELEASES);
        assertThat(walkPass(nodeB, store).generation())
                .as("the leftover pass was finished and a fresh one enumerated - two generations, one commit")
                .isEqualTo(2);
    }

    @Test
    void a_live_foreign_claim_defers_the_commit_rather_than_stealing_or_committing_partial() throws IOException {
        ArtifactStore store = store();
        seedReleases(store);
        // A foreign worker holds the pass's one segment with a long-lived claim (a slow node, not a dead one).
        ArtifactWalk foreign = walk(Map.of("walk.segments", "1", "walk.ttl", "900"));
        List<String> roots = List.of(StoreRepositoryInventory.publishedRoot());
        assertThatThrownBy(() -> foreign.walk(store, SearchIndexTask.CONSUMER, roots, key -> {
            throw new IllegalStateException("dies holding a live claim");
        })).isInstanceOf(IllegalStateException.class);

        sweep(store, walk(settings(1)));                               // must neither steal nor commit

        assertThat(store.readVersioned("index/search/current"))
                .as("a pass a live holder still owns defers the whole build to the next interval").isEmpty();
        assertThat(walkPass(foreign, store).complete()).as("the foreign claim was refused, not stolen").isFalse();
    }

    @Test
    void a_pass_another_live_worker_shared_in_is_never_committed() throws IOException {
        ArtifactStore store = store();
        seedReleases(store);
        // The splitting walk lets a foreign worker complete one whole segment of the task's own fresh pass and die
        // mid-way through the next - the doubly-held-lease shape the single-holder guard exists for. The task's
        // enumeration then never sees the foreign segment's releases in that generation. The foreign worker keeps
        // the default checkpoint stride so it dies exactly between segments, leaving its finished segment durably
        // DONE under its own holder id - the trace the guard reads.
        SplittingWalk walk = new SplittingWalk(walk(settings(4)),
                walk(Map.of("walk.segments", "4", "walk.ttl", "1")));

        sweep(store, walk);

        assertThat(walk.interfered).isTrue();
        assertThat(hits(query(store), "")).as("the committed index carries the foreign-held segment's releases " +
                "too - the shared pass was discarded and a fresh generation enumerated everything")
                .hasSize(RELEASES);
        assertThat(walkPass(walk, store).generation()).isEqualTo(2);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Whether {@code key} is a release's metadata document, not the coordinate's own. */
    private static boolean release(String key) {
        return key.startsWith(StoreRepositoryInventory.publishedRoot() + "/") && !key.endsWith("/@coordinate");
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    private static ArtifactWalk walk(Map<String, String> settings) {
        return WalkProvider.resolve(settings::get).orElseThrow();
    }

    private static Map<String, String> settings(int segments) {
        return Map.of(
                "walk.checkpoint", "1",         // commit the cursor after every item: a crash re-visits <= 1
                "walk.segments", Integer.toString(segments),
                "walk.ttl", "1");               // a crashed claim expires within the test's patience
    }

    private static WalkPass walkPass(ArtifactWalk walk, ArtifactStore store) throws IOException {
        return walk.pass(store, SearchIndexTask.CONSUMER).orElseThrow();
    }

    /** Two maven releases plus npm and pypi ones, so a segmented pass genuinely cuts across ecosystems. */
    private static void seedReleases(ArtifactStore store) throws IOException {
        publish(store, "maven", "org.example:lib1", "1.0");
        publish(store, "maven", "org.example:lib2", "1.0");
        publish(store, "maven", "org.example:lib3", "2.0");
        publish(store, "maven", "org.example:lib4", "2.0");
        publish(store, "npm", "@example/pkg", "3.1.0");
        publish(store, "pypi", "numpy", "2.0.1");
        publishPathAddressed(store, RAW_PATH);
    }

    /** The served path a raw upload is: bytes linked at a request path, with nothing recorded in the coordinate
     *  inventory - so it exists in the {@code publish/} pointer tree and nowhere in the metadata documents. Both
     *  rebuild
     *  paths have to find it there, which is the whole of what the second walk root is for. */
    private static void publishPathAddressed(ArtifactStore store, String requestPath) throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream(requestPath.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(requestPath, hash);
    }

    /** A raw upload, deliberately sorting after every seeded coordinate so a truncated index shows as a missing
     *  tail rather than passing by luck. */
    private static final String RAW_PATH = "/raw/notes/release-notes.txt";

    private static void publish(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String path = "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
    }

    private static void sweep(ArtifactStore store, ArtifactWalk walk) throws IOException {
        new SearchIndexTask(INTERVAL, walk).repository(context(store));
    }

    /** Re-run the sweep until the dead worker's claim expired and a whole index was committed; a run while the
     *  claim is still live claims nothing and commits nothing - refused, never stolen. Bounded by sweeps attempted,
     *  and loud when they run out: the cells below then read the recorded blob opens to tell a restart from a resume,
     *  and a wait that gave up quietly would hand them an empty recording - a claim about reads that were never
     *  made, passing as if the property held. */
    private static void awaitSweep(ArtifactStore store, ArtifactWalk walk) throws IOException {
        for (int pass = 0; pass < PASSES; pass++) {
            sweep(store, walk);
            List<String> served = hits(query(store), "");
            if (served != null && served.size() == RELEASES) {
                return;
            }
            pause();
        }
        throw new AssertionError("No sweep committed an index over all " + RELEASES + " releases across "
                + PASSES + " passes");
    }

    /** The gap between passes, so a claim whose expiry the next pass waits on has real time to lapse. */
    private static void pause() throws IOException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted awaiting the claim expiry", e);
        }
    }

    /** A query with an always-refresh reader over the raw backing store, so every committed generation is observed
     *  the moment its sweep publishes it. */
    private static SearchQuery query(ArtifactStore store) {
        return new LuceneSearchQueryProvider(Duration.ZERO).over(store, "default/app");
    }

    private static RepositoryContext context(ArtifactStore store) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return "app";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                // This suite is the walk-riding full-rebuild restart-on-crash machinery, which the task keeps
                // for bootstrap and the periodic reconcile. Pin the safety valve so every sweep is that full rebuild -
                // the incremental steady state does not ride the walk and has its own suite (SearchIncrementalTest).
                return key -> switch (key) {
                    case "search-incremental" -> "false";
                    case SearchMode.SETTING -> "true";         // the repository has asked for an index
                    default -> null;
                };
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }

    /** Delegates everything to the backend; subclasses observe or fail single calls. */
    private static abstract class ForwardingStore extends ForwardingArtifactStore {
        ForwardingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }
    }

    /** Fails the first read of the n-th release's document with an unchecked throw - the injected crash of the
     *  indexing worker mid-release. Unchecked deliberately: a process-death stand-in must not look like one bad row.
     *  Every other call passes through, so the walk's own state commits normally; the documents read before the crash
     *  are recorded. */
    private static final class CrashingStore extends ForwardingStore {

        private final List<String> read = new ArrayList<>();
        private final int crashOnNth;

        private CrashingStore(ArtifactStore delegate, int crashOnNth) {
            super(delegate);
            this.crashOnNth = crashOnNth;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (release(key) && !read.contains(key)) {
                read.add(key);
                if (read.size() == crashOnNth) {
                    throw new IllegalStateException("injected crash on " + key);
                }
            }
            return super.readVersioned(key);
        }
    }

    /** Counts release document reads per key - the sweep reads a delivered release's document once per
     *  enumeration, so a positive count is proof the release was enumerated again. */
    private static final class RecordingStore extends ForwardingStore {

        private final Map<String, Integer> read = new HashMap<>();

        private RecordingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (release(key)) {
                read.merge(key, 1, Integer::sum);
            }
            return super.readVersioned(key);
        }
    }

    /** Wraps the task's walk so its first {@code walk} call races a foreign worker into the same pass: the foreign
     *  instance completes one whole segment, dies mid-way through the next, and its claim is left to expire before
     *  the task's own walk proceeds - the deterministic stand-in for a doubly-held lease splitting one pass. */
    private static final class SplittingWalk implements ArtifactWalk {

        private final ArtifactWalk delegate;
        private final ArtifactWalk foreign;
        private boolean interfered;

        private SplittingWalk(ArtifactWalk delegate, ArtifactWalk foreign) {
            this.delegate = delegate;
            this.foreign = foreign;
        }

        @Override
        public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
                throws IOException {
            if (!interfered) {
                interfered = true;
                try {
                    foreign.walk(store, consumer, roots, new KeyVisitor() {
                        private int checkpoints;

                        @Override
                        public void visit(String key) {
                        }

                        @Override
                        public void beforeCheckpoint(String cursor) {
                            if (++checkpoints == 2) {
                                throw new IllegalStateException("foreign worker dies mid-second-segment");
                            }
                        }
                    });
                } catch (IllegalStateException _) {
                    // the foreign worker's death; its first segment is durably DONE under its own holder id
                }
                try {
                    Thread.sleep(1_200);                    // outlive the one-second claim TTL
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted outliving the foreign claim", e);
                }
            }
            return delegate.walk(store, consumer, roots, visitor);
        }

        @Override
        public Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException {
            return delegate.pass(store, consumer);
        }

        @Override
        public List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException {
            return delegate.segments(store, consumer);
        }
    }

    /** The coordinates one full page of {@code query} matches, or {@code null} when this repository has no usable
     *  index yet - which the SPI now says with an empty {@link Optional} rather than a {@code null} list.
     *  Every assertion below is about the rows, so the page is unwrapped here once. */
    private static List<String> hits(SearchQuery query, String text) throws IOException {
        return query.search(text, null, SearchQuery.MAX_PAGE)
                .map(page -> page.hits().stream().map(SearchQuery.Hit::display).toList()).orElse(null);
    }


}
