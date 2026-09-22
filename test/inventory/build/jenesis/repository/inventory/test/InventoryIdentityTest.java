package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore.Op;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The maintained rollup identity ({@link StoreRepositoryInventory#identity()} / {@link
 * StoreRepositoryInventory#rebuildIdentity()}): a small content-addressed digest over the published set that a
 * whole-repository export derives its ETag from. It is maintained incrementally at every mutation - a publish folds a
 * member in, an eviction folds it out, a declared-license change re-folds the affected member - and the incrementally
 * maintained value must always agree with an authoritative {@link StoreRepositoryInventory#rebuildIdentity()} recompute
 * over the same set. This pins that agreement across a publish, an eviction (the digest changes), a re-publish (it
 * returns), and a {@link LicenseInventory#record} that unions a license into a published member; that re-publishing a
 * member ALREADY in the set is edge-detected to a no-op (no double XOR-fold that would cancel a live member back out);
 * that a fold which loses a compare-and-set race is retried rather than dropped, and one that loses every bounded
 * retry drops the rollup for the next read to rebuild rather than leaving it stale; and that a rebuild in flight
 * while publishes land folds each member exactly once - the walk takes those published at or before its boundary,
 * the publishes take the rest, a fold that hands a member to the walk records the handoff so a walk it may have
 * already passed is run again rather than settled, and a stale boundary is taken over rather than waited for.
 */
class InventoryIdentityTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String COORD = "com.example:lib";
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    /** The authoritative digest with nothing else running: what every maintained value must equal. */
    private String quietRebuild() throws IOException {
        return hex(inventory().rebuildIdentity());
    }

    private byte[] rollup() throws IOException {
        return store.readVersioned("identity/rollup").orElseThrow().content();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void the_incremental_identity_tracks_eviction_and_re_publish_and_agrees_with_a_rebuild() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        inventory().record(ECO, COORD, "3.0.0", NOW);

        // First read lazily builds the accumulator over the whole live set; it must equal an authoritative rebuild.
        String full = inventory().identity();
        assertThat(full).as("the lazy build equals the authoritative recompute").isEqualTo(hex(inventory().rebuildIdentity()));

        // Evicting a member folds it out incrementally: the digest changes, and still matches a fresh rebuild.
        Release evicted = inventory().releases().stream()
                .filter(release -> release.version().equals("2.0.0")).findFirst().orElseThrow();
        inventory().evict(evicted);
        String afterEvict = inventory().identity();
        assertThat(afterEvict).as("evicting a member changes the rollup digest").isNotEqualTo(full);
        assertThat(afterEvict).as("the incremental fold-out agrees with a rebuild over the reduced set")
                .isEqualTo(hex(inventory().rebuildIdentity()));

        // Re-publishing the same member folds it back in: the digest returns to the original.
        inventory().record(ECO, COORD, "2.0.0", NOW);
        assertThat(inventory().identity()).as("the re-published member returns the digest to its original value")
                .isEqualTo(full);
    }

    @Test
    void a_declared_license_record_refolds_the_member_and_still_agrees_with_a_rebuild() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        String before = inventory().identity();                     // builds the accumulator over the license-less set

        // A declared-license union re-folds that one member (absent -> present license fingerprint), so the rollup
        // digest changes and must still equal an authoritative rebuild that reads the same declared set.
        new LicenseInventory(store).record(ECO, COORD, "1.0.0",
                List.of(new LicenseInventory.Declared("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0")));

        String after = inventory().identity();
        assertThat(after).as("unioning a license into a published member changes the rollup digest").isNotEqualTo(before);
        assertThat(after).as("the incremental license re-fold agrees with a full rebuild")
                .isEqualTo(hex(inventory().rebuildIdentity()));
    }

    @Test
    void re_publishing_an_already_published_member_is_an_identity_no_op_and_does_not_double_fold() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        String twoMembers = inventory().identity();      // the lazy build folds each published member in exactly once

        // Re-publish an ALREADY-published member with NO eviction between: the publish path is edge-triggered on the
        // absent -> present membership transition (StoreRepositoryInventory#record / InventoryRecording returns
        // firstPublish=false for a member already in the set), so it must NOT fold the member a second time. The fold
        // is an XOR, its own inverse, so a double-fold would cancel 2.0.0 back OUT of the accumulator - silently
        // dropping a live member from the whole-repository SBOM / NOTICE ETag the identity backs. A distinct publish
        // instant proves the doc-refresh path runs (the section is rewritten) while the identity stays put.
        inventory().record(ECO, COORD, "2.0.0", NOW.plus(Duration.ofHours(1)));

        assertThat(inventory().identity())
                .as("re-publishing a member already in the set leaves the rollup identity unchanged (no double-fold)")
                .isEqualTo(twoMembers);
        assertThat(inventory().identity())
                .as("and the maintained value still agrees with an authoritative rebuild over the unchanged set")
                .isEqualTo(hex(inventory().rebuildIdentity()));
    }

    @Test
    void a_publish_whose_fold_loses_its_race_retries_it_rather_than_dropping_it() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        String one = inventory().identity();             // the rollup exists, so the next publish folds into it

        // The one contended per-repository accumulator loses the publish's first compare-and-set - a storm's usual
        // outcome. The fold used to ride the document write's batch as one attempt whose outcome nobody read, so the
        // member was simply missing from the identity until the next reconcile: the identity-drift canary measured
        // thirty-two writers leaving a rollup that disagreed with a rebuild from truth, and no fold ever reported a loss.
        FaultInjectingStore contended = FaultInjectingStore.wrap(store);
        contended.conflictNext(FaultInjectingStore.keyContaining("identity/rollup"));
        new StoreRepositoryInventory(contended).record(ECO, COORD, "2.0.0", NOW);

        assertThat(store.readVersioned("identity/rollup"))
                .as("the rollup stands: the fold retried its lost race rather than dropping the rollup or the fold")
                .isPresent();
        assertThat(inventory().identity())
                .as("the member whose fold lost its first race is in the maintained identity")
                .isNotEqualTo(one)
                .isEqualTo(quietRebuild());
    }

    @Test
    void a_publish_during_a_rebuild_is_folded_exactly_once() throws Exception {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        inventory().identity();                          // settled over two members

        // A rebuild in flight: stamped with its boundary, inside the grace before its walk. Two publishes land
        // meanwhile - one stamped before the boundary, which the walk folds, one after it, which folds itself into the
        // stamped object - and the settled identity must equal a quiet rebuild's. That is what "exactly once" means
        // for an XOR fold: a member folded by both the walk and its publish cancels out of the identity; one folded by
        // neither is missing from it. Before the boundary every fold onto the absent rollup was a no-op, and the
        // rebuild stored what its walk had seen.
        ExecutorService rebuilder = Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            Future<byte[]> rebuild = rebuilder.submit(() -> inventory().rebuildIdentity());
            Instant deadline = Instant.now().plusSeconds(10);
            while (rollup().length == 32 && Instant.now().isBefore(deadline)) {
                Thread.sleep(10);
            }
            assertThat(rollup().length).as("the rebuild stamped the rollup with its boundary").isNotEqualTo(32);
            inventory().record(ECO, COORD, "3.0.0", NOW);                                       // the walk's
            // Dated past the in-flight rebuild's boundary (a second after its clock) but before the quiet rebuild's
            // below, which begins after the grace: a member dated further ahead than any rebuild's boundary is left
            // to a fold that has already happened, by every rebuild, until the clock passes it - the one shape a
            // publisher's clock running ahead by more than the allowance produces.
            inventory().record(ECO, COORD, "4.0.0", Instant.now().plusMillis(1500));             // its own fold's
            // The two records must land inside the rebuild's grace for the arrangement to hold; a machine that took
            // longer than the grace has not tested what this test claims, and says so rather than failing the product.
            Assumptions.assumeTrue(System.nanoTime() - started < Duration.ofMillis(1500).toNanos(),
                    "the publishes did not land inside the rebuild's grace on this machine");
            byte[] settled = rebuild.get(60, TimeUnit.SECONDS);
            assertThat(hex(settled))
                    .as("the settled identity folds the publish before the boundary and the one after it exactly once")
                    .isEqualTo(quietRebuild());
            assertThat(inventory().identity()).isEqualTo(hex(settled));
        } finally {
            rebuilder.shutdownNow();
        }
    }

    /**
     * A member dated before the boundary that lands while the walk is already running, not inside the grace.
     *
     * <p>Every other case here lands its publish in the GRACE - the window between the boundary and the walk, which
     * exists so a publish stamped before the boundary has time to write its member before the walk could pass it.
     * The fleet's storm does not: it publishes for as long as the rebuild runs, so a member can be written while the
     * walk is mid-enumeration. If that member is dated at or before the boundary its own fold declines it - "the
     * rebuild's own walk folds this member" - and whether the walk folds it depends on whether the enumeration had
     * already passed its key. Each half is correct on its own and they are asked in the wrong order, so before the
     * handoff counter the member was folded by nobody and the rollup was short by it until something rebuilt from
     * truth.
     *
     * <p>Constructed rather than timed, which is what makes it a falsifier. A sized seed and a sleep past the grace
     * does NOT reproduce this: the walk over a few hundred members finishes inside the grace-plus-one-second the
     * sleep waits, so the publish lands after the settle, is folded normally by its own publish, and the test goes
     * red for a reason that is not the defect (the rebuild's RETURN value is one member short, while the rollup it
     * left behind is exactly right). That version of this test was written, run and believed before the vacuity
     * guard below was added and fired. So the walk is held instead of raced: the store traces every read, the
     * enumeration is in key order, and holding the read of the LAST seeded version is a point at which the walk has
     * provably passed every earlier key. The member lands there, at a version that sorts first, dated a second
     * before the boundary.
     *
     * <p>The fleet's PeerClock scenario reaches the same shape about twice in sixty runs, loses a different member
     * each time, and cannot be instrumented while doing it - a probe inside the walk suppresses the race - which is
     * why the interleaving is built here rather than hunted there.
     */
    @Test
    void a_member_dated_before_the_boundary_that_lands_during_the_walk_is_folded_exactly_once() throws Exception {
        for (int index = 0; index < 20; index++) {
            inventory().record(ECO, COORD, String.format("1.0.%02d", index), NOW);
        }
        inventory().identity();                          // settled over the seeded members

        // Hold the walk at the last member key it will read. Fixed-width versions so the string order the walk
        // enumerates in is the numeric one, and the hold is one-shot: the walk the settle runs AGAIN must not block.
        CountDownLatch enumerating = new CountDownLatch(1);
        CountDownLatch landed = new CountDownLatch(1);
        AtomicBoolean held = new AtomicBoolean();
        FaultInjectingStore traced = FaultInjectingStore.wrap(store);
        traced.tracing((op, key) -> {
            if (op != Op.READ_VERSIONED || key == null || !key.endsWith("/1.0.19")
                    || !held.compareAndSet(false, true)) {
                return;
            }
            enumerating.countDown();
            try {
                landed.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ExecutorService rebuilder = Executors.newSingleThreadExecutor();
        try {
            StoreRepositoryInventory rebuilding = new StoreRepositoryInventory(traced);
            Future<byte[]> rebuild = rebuilder.submit(rebuilding::rebuildIdentity);
            assertThat(enumerating.await(60, TimeUnit.SECONDS))
                    .as("vacuity guard: the walk must be HELD mid-enumeration, not finished - this test says nothing "
                            + "about a member that lands after the settle")
                    .isTrue();
            byte[] stamped = rollup();
            assertThat(stamped.length).as("and the rollup is stamped: the rebuild is in flight").isNotEqualTo(32);

            // Dated a second BEFORE the boundary, at a version that sorts before every seeded one: its own fold
            // declines it to the walk, and the walk is provably past the key it sorts at.
            inventory().record(ECO, COORD, "0.9.0", false, boundaryOf(stamped).minusSeconds(1));
            landed.countDown();

            rebuild.get(120, TimeUnit.SECONDS);
            // Read the maintained value BEFORE the quiet rebuild, which overwrites it. The claim is about what the
            // rollup HOLDS once the rebuild has settled, never about the value that rebuild returned.
            assertThat(inventory().identity())
                    .as("a member dated before the boundary and written at a key the walk had already passed is "
                            + "folded exactly once - by the walk RUN AGAIN, because the fold recorded the handoff")
                    .isEqualTo(quietRebuild());
        } finally {
            landed.countDown();
            rebuilder.shutdownNow();
        }
    }

    /** The boundary instant a stamped rollup carries: the eight bytes after the folds-since accumulator. */
    private static Instant boundaryOf(byte[] stamped) {
        return Instant.ofEpochMilli(ByteBuffer.wrap(stamped, 32, 8).getLong());
    }

    /** The handoff count a stamped rollup carries: the four bytes after the boundary - how many folds have declined a
     *  member to the rebuild's walk since it stamped. */
    private static int handoffsOf(byte[] stamped) {
        return ByteBuffer.wrap(stamped, 40, 4).getInt();
    }

    @Test
    void a_member_older_than_a_rebuilds_boundary_is_left_to_its_walk() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().identity();
        // Stamp the rollup by hand as a rebuild over a settled value would: the folds since (none), a boundary an
        // hour ahead, a handoff count of none, and the settled value readers keep answering from. A fold of a member
        // published before the boundary is the walk's, so it must leave the accumulator alone - and RECORD the
        // handoff, because whether the walk folds it depends on where its key sorts against an enumeration already
        // in flight, which this side cannot know. One published after the boundary must land in the folds since, and
        // in what readers answer, while the boundary, the handoff count and the settled value stand.
        byte[] settled = rollup();
        byte[] stamped = ByteBuffer.allocate(32 + 8 + 4 + 32).put(new byte[32])
                .putLong(Instant.now().plus(Duration.ofHours(1)).toEpochMilli()).putInt(0).put(settled).array();
        assertThat(store.writeVersioned("identity/rollup", stamped,
                store.readVersioned("identity/rollup").orElseThrow().token())).isTrue();

        inventory().record(ECO, COORD, "2.0.0", NOW);
        byte[] handed = rollup();
        assertThat(handed).as("a member published before the boundary leaves the object the same shape")
                .hasSize(stamped.length);
        assertThat(Arrays.copyOfRange(handed, 0, 32))
                .as("a member published before the boundary is the walk's to fold, not this fold's")
                .isEqualTo(new byte[32]);
        assertThat(handoffsOf(handed))
                .as("and the handoff is recorded, so a walk that may already have passed its key is run again")
                .isEqualTo(1);
        assertThat(Arrays.copyOfRange(handed, 32, 40)).as("the boundary stands")
                .isEqualTo(Arrays.copyOfRange(stamped, 32, 40));
        assertThat(Arrays.copyOfRange(handed, 44, handed.length)).as("the settled value stands")
                .isEqualTo(Arrays.copyOfRange(stamped, 44, stamped.length));

        inventory().record(ECO, COORD, "3.0.0", Instant.now().plus(Duration.ofHours(2)));
        byte[] after = rollup();
        assertThat(after).as("a member published after the boundary folds into the stamped object")
                .hasSize(stamped.length).isNotEqualTo(handed);
        assertThat(Arrays.copyOfRange(after, 32, after.length))
                .as("the boundary, the handoff count and the settled value stand; only the folds since moved")
                .isEqualTo(Arrays.copyOfRange(handed, 32, handed.length));
        assertThat(inventory().identity())
                .as("readers answer the settled value with the folds since applied - never nothing, never the stale value")
                .isNotEqualTo(hex(settled));
    }

    @Test
    void a_stale_rebuild_stamp_is_taken_over_rather_than_waited_for() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        // A crashed rebuild's leftovers: a stamped rollup whose boundary is an hour old and that settled nothing. A
        // reader must not wait the stale horizon out on it; the next rebuild takes it over.
        byte[] stale = ByteBuffer.allocate(32 + 8 + 4).put(new byte[32])
                .putLong(Instant.now().minus(Duration.ofHours(1)).toEpochMilli()).putInt(0).array();
        assertThat(store.writeVersioned("identity/rollup", stale, null)).isTrue();

        long started = System.nanoTime();
        String identity = inventory().identity();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("taken over at once, not waited out")
                .isLessThan(Duration.ofSeconds(60));
        assertThat(identity).isEqualTo(quietRebuild());
        assertThat(rollup()).as("the rollup is settled again").hasSize(32);
    }

    @Test
    void a_fold_that_loses_every_cas_retry_drops_the_rollup_and_the_next_read_rebuilds_it() throws IOException {
        inventory().record(ECO, COORD, "1.0.0", NOW);
        inventory().record(ECO, COORD, "2.0.0", NOW);
        inventory().record(ECO, COORD, "3.0.0", NOW);
        String threeMembers = inventory().identity();    // the lazy build over the whole live set

        // Evict a member, but make the single contended per-repository accumulator lose EVERY one of combine()'s
        // bounded compare-and-set retries - the conflict a concurrent publish/eviction storm produces on the one object
        // they all fold. One single-shot conflict per retry on the rollup key exhausts the budget. The fold-out is
        // then dropped WITH the rollup rather than failing the eviction it rides: it used to leave the stale rollup
        // standing for the reconcile to heal, and every conditional read until then answered from it - the
        // identity-drift canary measured thirty-two writers leaving a rollup that disagreed with a rebuild from truth.
        FaultInjectingStore contended = FaultInjectingStore.wrap(store);
        for (int retry = 0; retry < Retries.COMPARE_AND_SET; retry++) {
            contended.conflictNext(FaultInjectingStore.keyContaining("identity/rollup"));   // InventoryIdentity.KEY
        }
        Release evicted = inventory().releases().stream()
                .filter(release -> release.version().equals("2.0.0")).findFirst().orElseThrow();
        new StoreRepositoryInventory(contended).evict(evicted);

        // The eviction itself committed - 2.0.0 is gone from the published set - and every fold-out retry lost, so the
        // rollup is gone with it: the next read finds no accumulator and rebuilds from the live set.
        assertThat(inventory().coordinates())
                .as("the eviction committed even though its rollup fold-out was dropped after exhausting its retries")
                .extracting(StoreRepositoryInventory.Coordinate::version)
                .containsExactlyInAnyOrder("1.0.0", "3.0.0");
        assertThat(contended.calls(Op.WRITE_VERSIONED))
                .as("the fold-out exhausted every bounded compare-and-set retry against the injected conflict")
                .isGreaterThanOrEqualTo(Retries.COMPARE_AND_SET);
        assertThat(store.readVersioned("identity/rollup"))
                .as("the exhausted fold-out dropped the rollup rather than leaving it stale")
                .isEmpty();
        String reread = inventory().identity();
        assertThat(reread)
                .as("the next read rebuilds the identity from the reduced live set, never serving the stale value")
                .isNotEqualTo(threeMembers)
                .isEqualTo(hex(inventory().rebuildIdentity()));
        assertThat(store.readVersioned("identity/rollup"))
                .as("and stores the rebuilt rollup for the reads after it").isPresent();
    }
}
