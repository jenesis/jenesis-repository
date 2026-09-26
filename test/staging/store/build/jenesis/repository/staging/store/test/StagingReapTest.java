package build.jenesis.repository.staging.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.staging.StagingState;
import build.jenesis.repository.staging.store.StagingReapTask;
import build.jenesis.repository.staging.store.StagingReapTaskProvider;
import build.jenesis.repository.staging.store.StoreStaging;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The staging key spaces stop growing: a deploy stamps the id's {@code OPEN} marker so the staging list
 * shows it and the reap can age it, an abandoned-OPEN id past the TTL has its staged artifacts dropped and its
 * marker removed (so its blobs fall to the blob GC), a sealed ({@code PROMOTED}/{@code DROPPED}) marker past the
 * TTL is deleted while a younger one keeps rejecting the sealed transitions, and the two legacy leftovers - a
 * timestamp-less marker and a marker-less staged tree - are stamped on first observation and reaped a TTL later.
 */
class StagingReapTest {

    private static final Duration TTL = Duration.ofDays(30);

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreStaging staging;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        staging = new StoreStaging(store);
    }

    @Test
    void a_deploy_stamps_the_open_marker_so_the_staging_list_shows_the_id() throws IOException {
        staging.stage("s1", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        assertThat(staging.ids(100).ids()).containsExactly("s1");
        assertThat(staging.state("s1")).isEqualTo(StagingState.OPEN);
    }

    @Test
    void an_abandoned_open_staging_is_dropped_after_the_ttl() throws IOException {
        staging.stage("stale", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));

        assertThat(staging.reap(Instant.now(), TTL)).as("younger than the TTL").isZero();
        assertThat(staging.staged("stale")).hasSize(1);

        assertThat(staging.reap(Instant.now().plus(TTL).plusSeconds(1), TTL)).isEqualTo(1);
        assertThat(staging.staged("stale")).as("staged pointers unpublished").isEmpty();
        assertThat(staging.ids(100).ids()).as("marker removed").isEmpty();
        assertThat(new Publication(store).blob("/staging/stale/maven/org/example/a/1/a-1.jar")).isEmpty();
    }

    @Test
    void a_stage_that_refreshes_the_marker_inside_the_reap_window_is_not_reaped() throws IOException {
        // TOCTOU regression: the reap reads the marker/state/TTL BEFORE it takes the single-writer lock, then acts. A
        // stage/promote/drop that wins the lock in that window refreshes/seals the marker; the reap must re-validate
        // UNDER the lock and skip, or it deletes a just-refreshed staging (data loss). The wrapper stands in for the
        // racing writer: the reap's FIRST (pre-lock) read of the marker re-stamps the underlying marker to `sweep`
        // (as a resumed OPEN would) and returns the stale past-TTL view the reap decided on; the reap's second
        // (under-lock) read then sees the fresh marker and must skip. With the fix this reaps nothing; without it (a
        // single pre-lock read) the reap would delete the just-refreshed pointer and its live marker.
        staging.stage("racer", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        Instant sweep = Instant.now().plus(TTL).plusSeconds(1);      // the real OPEN marker is past the TTL at sweep time
        StoreStaging racing = new StoreStaging(new RefreshOnFirstMarkerRead(store, "staging-state/racer", sweep));

        assertThat(racing.reap(sweep, TTL))
                .as("the marker was refreshed inside the reap window - the under-lock re-validation skips it").isZero();
        assertThat(staging.staged("racer")).as("the staged pointer is preserved, not deleted").hasSize(1);
        assertThat(staging.ids(100).ids()).as("the live marker survives the reap").containsExactly("racer");
    }

    /** A store that, on the FIRST {@code readVersioned} of one marker key, re-stamps the underlying marker to
     *  {@code refreshed} (standing in for a stage/promote that won the single-writer lock in the reap's check-then-act
     *  window) and returns the ORIGINAL, stale value the reap decided on. Every other call delegates unchanged. */
    private static final class RefreshOnFirstMarkerRead implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private final String markerKey;
        private final Instant refreshed;
        private boolean refreshedOnce;

        private RefreshOnFirstMarkerRead(ArtifactStore delegate, String markerKey, Instant refreshed) {
            this.delegate = delegate;
            this.markerKey = markerKey;
            this.refreshed = refreshed;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            Optional<Versioned> value = delegate.readVersioned(key);
            if (key.equals(markerKey) && !refreshedOnce && value.isPresent()) {
                refreshedOnce = true;   // the racing writer commits a fresh OPEN marker into the store
                delegate.writeVersioned(key,
                        (StagingState.OPEN.name() + " " + refreshed).getBytes(StandardCharsets.UTF_8),
                        value.get().token());
            }
            return value;               // the reap's pre-lock read still sees the ORIGINAL stale marker
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
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    @Test
    void a_rival_that_steals_the_lapsed_lease_mid_reap_is_not_wiped_by_the_reap() throws IOException {
        // The reap acquires the id's lease, then unpublishes each staged pointer and deletes the state
        // marker. If that loop outruns the lease TTL (a large abandoned tree), a rival stage() steals the lapsed lease
        // and re-stamps a fresh OPEN marker + links a new pointer; a reap that ran its mutations UNCONDITIONALLY would
        // then drop the rival's fresh pointer and delete its just-written marker (silent loss of an accepted deploy).
        // The fix routes every reap mutation through lock.guarded, re-asserting ownership first - so on a stolen lease
        // it STOPS and leaves the rival's staging intact. The delegating store overwrites the lease with a rival owner
        // on the reap's staged() walk (standing in for the rival that won the lapsed lease mid-loop).
        staging.stage("big", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        Instant sweep = Instant.now().plus(TTL).plusSeconds(1);      // past the TTL: the reap acquires and enters the loop
        StoreStaging reaper = new StoreStaging(new StealLeaseOnStagedWalk(store, "big"));

        assertThat(reaper.reap(sweep, TTL))
                .as("the reap lost the lease to a rival mid-loop and stopped - reaping nothing").isZero();
        assertThat(staging.staged("big"))
                .as("the rival's staged pointer is NOT unpublished by the lease-losing reap").hasSize(1);
        assertThat(staging.ids(100).ids())
                .as("the rival's marker is NOT deleted by the lease-losing reap").containsExactly("big");
    }

    /** A store that, on the reap's FIRST {@code list} of an id's {@code publish/staging/<id>} tree (the {@code staged}
     *  walk, run just before the guarded unpublish loop), overwrites the id's {@code staging-lock/<id>} lease with a
     *  DIFFERENT owner - standing in for a rival {@code stage()} that stole the lapsed lease mid-reap. Every other call
     *  delegates unchanged, so the reap's guarded ownership re-assertion reads the stolen lease through this same view
     *  and fails, stopping the reap. */
    private static final class StealLeaseOnStagedWalk implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private final String id;
        private boolean stolen;

        private StealLeaseOnStagedWalk(ArtifactStore delegate, String id) {
            this.delegate = delegate;
            this.id = id;
        }

        @Override
        public List<String> list(String prefix) {
            if (!stolen && prefix.startsWith("publish/staging/" + id)) {
                stolen = true;                                   // the rival wins the lapsed lease exactly once
                try {
                    Optional<Versioned> lease = delegate.readVersioned("staging-lock/" + id);
                    if (lease.isPresent()) {
                        delegate.writeVersioned("staging-lock/" + id,
                                ("rival\n" + Instant.now().plus(Duration.ofMinutes(2)))
                                        .getBytes(StandardCharsets.UTF_8),
                                lease.get().token());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return delegate.list(prefix);
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
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    @Test
    void a_sealed_marker_is_removed_after_the_ttl_but_guards_within_it() throws IOException {
        staging.stage("s2", "/maven/org/example/b/1/b-1.jar", "b".getBytes(StandardCharsets.UTF_8));
        staging.promote("s2");

        staging.reap(Instant.now(), TTL);
        assertThat(staging.state("s2")).as("younger than the TTL: the seal still holds").isEqualTo(StagingState.PROMOTED);
        assertThatIllegalStateException().isThrownBy(
                () -> staging.stage("s2", "/maven/org/example/b/2/b-2.jar", "b2".getBytes(StandardCharsets.UTF_8)));

        assertThat(staging.reap(Instant.now().plus(TTL).plusSeconds(1), TTL)).isEqualTo(1);
        assertThat(staging.ids(100).ids()).as("sealed marker no longer leaks").isEmpty();
        assertThat(new Publication(store).blob("/maven/org/example/b/1/b-1.jar"))
                .as("the promoted artifact itself stays published").isPresent();
    }

    @Test
    void a_residual_staged_pointer_under_a_sealed_id_is_unpublished_by_the_reap() throws IOException {
        // A stage that raced a seal (or a crash mid-mutation that outlived the single-writer lease) can leave a
        // residual publish/staging pointer under an already-sealed id. The seal never revisits the id, so the reap
        // must walk and unpublish any residual staged pointer tree when it collects the sealed marker - otherwise it
        // leaks forever (and stays served but for the withhold screen). The single-writer lock prevents it at write
        // time; the reap is the crash-recovery backstop.
        staging.stage("sealed", "/maven/org/example/b/1/b-1.jar", "b".getBytes(StandardCharsets.UTF_8));
        staging.promote("sealed");
        assertThat(staging.staged("sealed")).as("promotion unpublished its own staged tree").isEmpty();

        // Simulate the residual pointer landing after the seal (what the lock prevents; what the reap cleans up).
        Publication publication = new Publication(store);
        publication.link("/staging/sealed/maven/org/example/b/2/b-2.jar",
                publication.storeBlob(new ByteArrayInputStream("residual".getBytes(StandardCharsets.UTF_8))));
        assertThat(staging.staged("sealed")).as("the residual sits under the sealed id").hasSize(1);

        assertThat(staging.reap(Instant.now().plus(TTL).plusSeconds(1), TTL)).isEqualTo(1);
        assertThat(staging.ids(100).ids()).as("the sealed marker is removed").isEmpty();
        assertThat(new Publication(store).blob("/staging/sealed/maven/org/example/b/2/b-2.jar"))
                .as("the residual staged pointer is unpublished, not leaked").isEmpty();
    }

    @Test
    void a_tree_whose_marker_was_lost_is_stamped_on_first_observation_and_reaped_a_ttl_later() throws IOException {
        // A staged tree whose marker a partial purge of the staging-state space took, and beside it a marker that
        // is not "<STATE> <instant>" - not one this class wrote, so never one it deletes.
        store.writeVersioned("staging-state/foreign", StagingState.DROPPED.name().getBytes(StandardCharsets.UTF_8),
                null);
        Publication publication = new Publication(store);
        publication.link("/staging/orphan-tree/maven/org/example/c/1/c-1.jar",
                publication.storeBlob(new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8))));

        Instant firstPass = Instant.now();
        assertThat(staging.reap(firstPass, TTL)).as("first observation only stamps").isZero();
        assertThat(staging.state("orphan-tree")).isEqualTo(StagingState.OPEN);

        assertThat(staging.reap(firstPass.plus(TTL).plusSeconds(1), TTL)).isEqualTo(1);
        assertThat(staging.staged("orphan-tree")).isEmpty();
        assertThat(store.readVersioned("staging-state/foreign").map(marker -> new String(marker.content(),
                StandardCharsets.UTF_8))).as("a marker without its instant is left as it was").contains("DROPPED");
    }

    @Test
    void the_reap_rides_the_scheduled_cleanup_enablement() {
        StagingReapTaskProvider provider = new StagingReapTaskProvider();
        assertThat(provider.create(key -> "scheduled-cleanup".equals(key) ? "false" : null))
                .as("the dial still switches it off").isEmpty();
        Optional<MaintenanceTask> task = provider.create(key -> null);
        assertThat(task).isPresent();
        assertThat(task.get().name()).isEqualTo("staging-reap");
    }

    @Test
    void a_malformed_cleanup_interval_falls_back_rather_than_aborting_the_resolve() {
        // A malformed dial (env/property bypasses the settings DURATION write-validation) must degrade to the default
        // cadence, never throw a DateTimeParseException out of create() - that aborts the whole maintenance-task
        // resolve and drops every pass. The value came from a real bug this sweep found unhardened in this module.
        Optional<MaintenanceTask> task = new StagingReapTaskProvider().create(key -> switch (key) {
            case "scheduled-cleanup" -> "true";
            case "cleanup-interval" -> "every-hour";
            default -> null;
        });
        assertThat(task).as("the provider still yields a task at the default cadence").isPresent();
        assertThat(task.get().interval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void a_malformed_staging_ttl_falls_back_to_the_default_rather_than_throwing_or_disabling() throws IOException {
        staging.stage("stale", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        // Far past any plausible default TTL, so a fallback (a positive default) reaps the id while a disable (null)
        // or a throw would leave it: proves the garbled live dial degraded to the default, not out of the pass hook.
        Instant farFuture = Instant.now().plus(Duration.ofDays(3650));
        new StagingReapTask(Duration.ofHours(1)).repository(
                context(farFuture, key -> "staging-ttl".equals(key) ? "not-a-duration" : null));
        assertThat(staging.ids(100).ids()).as("the reap ran at the default TTL rather than aborting on the malformed dial")
                .isEmpty();
    }

    @Test
    void a_zero_ttl_disables_the_reap_so_an_abandoned_id_survives() throws IOException {
        // A documented off switch: staging-ttl='PT0S' disables the reap. Even far past any plausible age the
        // abandoned OPEN id must survive, proving ttl() mapped the zero duration to null (no reap), not to a default.
        staging.stage("stale", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        Instant farFuture = Instant.now().plus(Duration.ofDays(3650));
        new StagingReapTask(Duration.ofHours(1)).repository(
                context(farFuture, key -> "staging-ttl".equals(key) ? "PT0S" : null));
        assertThat(staging.ids(100).ids()).as("PT0S disables the reap: the abandoned id is left untouched")
                .containsExactly("stale");
        assertThat(staging.staged("stale")).as("its staged pointers are not unpublished").hasSize(1);
    }

    @Test
    void a_blank_ttl_disables_the_reap_so_an_abandoned_id_survives() throws IOException {
        staging.stage("stale", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        Instant farFuture = Instant.now().plus(Duration.ofDays(3650));
        new StagingReapTask(Duration.ofHours(1)).repository(
                context(farFuture, key -> "staging-ttl".equals(key) ? "   " : null));
        assertThat(staging.ids(100).ids()).as("a blank ttl disables the reap: the abandoned id is left untouched")
                .containsExactly("stale");
        assertThat(staging.staged("stale")).as("its staged pointers are not unpublished").hasSize(1);
    }

    @Test
    void a_corrupt_marker_reads_as_open_and_never_breaks_the_listing() throws IOException {
        staging.stage("good", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        // A garbled/foreign marker whose first token is not a StagingState (a torn write, a future state name).
        store.writeVersioned("staging-state/garbled",
                "WAT 2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);
        assertThat(staging.state("garbled")).as("an unreadable marker reads as OPEN, never throws")
                .isEqualTo(StagingState.OPEN);
        // GET /api/repository/staging walks every id calling state(); one bad marker must not throw out of the whole listing.
        assertThat(staging.ids(100).ids()).contains("good", "garbled");
        for (String id : staging.ids(100).ids()) {
            assertThat(staging.state(id)).as("every id resolves a state across the walk").isNotNull();
        }
    }

    /** A minimal maintenance context over the test's own store, so a task's per-repository hook can be driven with a
     *  chosen wall clock and configuration. */
    private RepositoryContext context(Instant now, UnaryOperator<String> config) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
            }

            @Override
            public String repository() {
                return "releases";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return config;
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
