package build.jenesis.repository.staging.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.staging.store.StoreStaging;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.staging.StagingState;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.staging.store.StagingWithholdInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Store-backed staging over a real filesystem artifact store: staged artifacts are held out of the release layout
 * and listed under their id, promotion publishes every one (a staged module jar gaining its module view) and is
 * terminal, dropping discards, and the sealed transitions are rejected.
 */
class StoreStagingTest {

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
    void staged_artifacts_are_held_then_promoted_into_the_release_layout() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "pom-bytes".getBytes(StandardCharsets.UTF_8);
        staging.stage("s1", "/maven/org/example/lib/1.0/lib-1.0.jar", jar);
        staging.stage("s1", "/maven/org/example/lib/1.0/lib-1.0.pom", pom);

        assertThat(staging.staged("s1")).containsExactlyInAnyOrder(
                "/maven/org/example/lib/1.0/lib-1.0.jar", "/maven/org/example/lib/1.0/lib-1.0.pom");
        assertThat(serve("/maven/org/example/lib/1.0/lib-1.0.jar")).as("held, not yet released").isNull();

        staging.promote("s1");
        assertThat(staging.state("s1")).isEqualTo(StagingState.PROMOTED);
        assertThat(staging.staged("s1")).isEmpty();
        assertThat(serve("/maven/org/example/lib/1.0/lib-1.0.jar")).isEqualTo(jar);
        assertThat(serve("/maven/org/example/lib/1.0/lib-1.0.pom")).isEqualTo(pom);
    }

    @Test
    void promotion_gives_a_module_jar_its_module_view() throws IOException {
        byte[] jar = automaticModuleJar("test.widget");
        staging.stage("s2", "/maven/org/example/widget/1.0/widget-1.0.jar", jar);
        staging.promote("s2");
        assertThat(serve("/maven/org/example/widget/1.0/widget-1.0.jar")).isEqualTo(jar);
        assertThat(serve("/module/test.widget/1.0/test.widget.jar")).as("module view gained on promotion").isEqualTo(jar);
    }

    @Test
    void a_dropped_id_discards_and_cannot_be_promoted() throws IOException {
        staging.stage("s3", "/maven/org/example/junk/1.0/junk-1.0.jar", "junk".getBytes(StandardCharsets.UTF_8));
        staging.drop("s3");
        assertThat(staging.state("s3")).isEqualTo(StagingState.DROPPED);
        assertThat(serve("/maven/org/example/junk/1.0/junk-1.0.jar")).isNull();
        assertThatIllegalStateException().isThrownBy(() -> staging.promote("s3"));
    }

    @Test
    void a_promoted_id_cannot_be_staged_into_again() throws IOException {
        staging.stage("s4", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        staging.promote("s4");
        assertThatIllegalStateException().isThrownBy(
                () -> staging.stage("s4", "/maven/org/example/a/2/a-2.jar", "a2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_marker_less_staged_tree_is_listed_and_promotable_without_a_reap() throws IOException {
        // A staged tree that predates the state-marker mechanism (or whose marker was lost to a partial purge of the
        // staging-state key-space): held content under publish/staging with no staging-state/<id> marker. Before the
        // self-heal, ids() listed only the markers, so this real staging was invisible to the console's staging list
        // and could be neither reviewed, promoted nor dropped - a silently-incomplete view served as whole - until
        // the opt-in reap eventually stamped-then-GC'd it. It must now surface and be fully operable straight from the
        // store with no reap pass required (self-healing over a store enabled-over pre-existing staged content).
        byte[] jar = "legacy-jar".getBytes(StandardCharsets.UTF_8);
        Publication publication = new Publication(store);
        publication.link("/staging/legacy/maven/org/example/old/1.0/old-1.0.jar",
                publication.storeBlob(new ByteArrayInputStream(jar)));

        assertThat(staging.ids(100).ids()).as("a marker-less held tree surfaces in the staging list").contains("legacy");
        assertThat(staging.state("legacy")).as("a marker-less id defaults to OPEN").isEqualTo(StagingState.OPEN);
        assertThat(staging.staged("legacy")).containsExactly("/maven/org/example/old/1.0/old-1.0.jar");

        staging.promote("legacy");    // reviewable and releasable straight away, no reap pass needed to stamp it first
        assertThat(staging.state("legacy")).isEqualTo(StagingState.PROMOTED);
        assertThat(serve("/maven/org/example/old/1.0/old-1.0.jar")).as("promoted into the release layout").isEqualTo(jar);
    }

    @Test
    void a_corrupt_state_marker_does_not_block_a_fresh_deploy() throws IOException {
        // A torn/foreign staging-state marker whose first token is not a StagingState. state()/promote()/drop() all
        // read it as OPEN (the totality); stage() was the lone holdout - a raw StagingState.valueOf that threw
        // IllegalArgumentException, which the controller maps to a 400 - so a deploy into an id whose marker got
        // garbled was rejected while every other operation treated the id as open. It now degrades to OPEN too.
        store.writeVersioned("staging-state/garbled",
                "WAT 2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);
        staging.stage("garbled", "/maven/org/example/g/1.0/g-1.0.jar", "g".getBytes(StandardCharsets.UTF_8));
        assertThat(staging.staged("garbled")).containsExactly("/maven/org/example/g/1.0/g-1.0.jar");
        assertThat(staging.state("garbled")).as("the deploy re-stamped the garbled marker OPEN").isEqualTo(StagingState.OPEN);
    }

    @Test
    void promotion_seals_the_state_even_when_the_marker_write_first_conflicts() throws IOException {
        // promote() re-publishes and unpublishes the staged artifacts BEFORE it seals the id PROMOTED, so a lost
        // compare-and-set on the state marker (a concurrent stage-refresh, reap stamp or second promote bumping the
        // token) must not silently drop the seal - that would leave the id OPEN and re-promotable while its artifacts
        // are already released. The marker write now retries, so the seal lands despite the conflict.
        ConflictOnceOnStateStore conflicting = new ConflictOnceOnStateStore(store);
        StoreStaging guarded = new StoreStaging(conflicting);
        guarded.stage("s5", "/maven/org/example/lib/1.0/lib-1.0.jar", "jar".getBytes(StandardCharsets.UTF_8));
        conflicting.arm();   // the next staging-state write loses its CAS once, as a concurrent stamp would

        guarded.promote("s5");

        assertThat(conflicting.stateConflicts).as("the state-marker write was forced to lose once").isEqualTo(1);
        assertThat(guarded.state("s5")).as("the PROMOTED seal still lands despite the lost CAS")
                .isEqualTo(StagingState.PROMOTED);
    }

    @Test
    void promotion_that_cannot_seal_after_repeated_conflicts_fails_closed_rather_than_silently_leaving_it_open()
            throws IOException {
        // The fail-closed counterpart of promotion_seals_the_state_even_when_the_marker_write_first_conflicts: if the
        // state-marker CAS loses on EVERY retry (a persistently-contended id), the seal can never land - and promote()
        // must surface that as an IOException rather than returning normally, which would silently leave the id OPEN and
        // re-promotable while its artifacts are already released. A refactor that dropped the fail-closed throw would
        // still pass the single-conflict test above; only forcing every retry to lose catches it.
        ConflictOnceOnStateStore conflicting = new ConflictOnceOnStateStore(store);
        StoreStaging guarded = new StoreStaging(conflicting);
        guarded.stage("s5x", "/maven/org/example/lib/1.0/lib-1.0.jar", "jar".getBytes(StandardCharsets.UTF_8));
        conflicting.armAlways();   // every staging-state write loses its CAS - the seal can never land

        assertThatThrownBy(() -> guarded.promote("s5x"))
                .as("a seal that cannot land after repeated conflicts throws, never returns a false success")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("staging-state/s5x");   // the one compare-and-set message, naming the key
        assertThat(conflicting.stateConflicts).as("the seal exhausted its bounded retries before failing closed")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void promotion_refuses_and_retains_a_staged_path_no_format_claims() throws IOException {
        // The data-loss bug: promote() unpublished a staged pointer even when no installed format claimed it, so the
        // file was dropped while the id sealed PROMOTED and the client saw 200. Promotion is now all-or-nothing: an
        // unclaimed path fails the whole promotion loudly BEFORE anything is mutated, and every staged file is retained.
        staging.stage("s6", "/maven/org/example/lib/1.0/lib-1.0.jar", "jar".getBytes(StandardCharsets.UTF_8));
        staging.stage("s6", "/unclaimed/thing-1.0.dat", "orphan".getBytes(StandardCharsets.UTF_8));

        assertThatIllegalStateException().isThrownBy(() -> staging.promote("s6"))
                .withMessageContaining("/unclaimed/thing-1.0.dat");

        assertThat(staging.state("s6")).as("nothing was sealed - the id stays open").isEqualTo(StagingState.OPEN);
        assertThat(staging.staged("s6")).as("every staged file retained, none dropped")
                .containsExactlyInAnyOrder("/maven/org/example/lib/1.0/lib-1.0.jar", "/unclaimed/thing-1.0.dat");
        assertThat(new Publication(store).blob("/staging/s6/unclaimed/thing-1.0.dat"))
                .as("the unclaimed staged pointer is retained, not silently dropped").isPresent();
        assertThat(serve("/maven/org/example/lib/1.0/lib-1.0.jar"))
                .as("no path was released - the promotion refused before any publish").isNull();
    }

    @Test
    void staged_content_is_withheld_from_serving_before_promotion() throws IOException {
        // The staged pointer lives at publish/staging/<id><releasePath>; without the withhold screen a GET at
        // /repository/<tenant>/<repo>/staging/<id>/... in a Maven repository resolved it and served the un-gated staged bytes. It must be withheld
        // from every read until promotion re-publishes it into its real (served) release path.
        staging.stage("s7", "/maven/org/example/held/1.0/held-1.0.jar", "held".getBytes(StandardCharsets.UTF_8));
        assertThat(serve("/staging/s7/maven/org/example/held/1.0/held-1.0.jar"))
                .as("staged content is not resolvable pre-promotion").isNull();
        assertThat(staging.staged("s7")).as("the lifecycle still sees the staged pointer (read directly, not served)")
                .containsExactly("/maven/org/example/held/1.0/held-1.0.jar");

        staging.promote("s7");
        assertThat(serve("/maven/org/example/held/1.0/held-1.0.jar")).as("served once promoted into the release layout")
                .isEqualTo("held".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void the_withhold_screen_withholds_only_the_staging_subtree() throws IOException {
        StagingWithholdInterceptor screen = new StagingWithholdInterceptor();
        assertThat(screen.withheld("/staging/s1/maven/org/example/x/1/x-1.jar", store)).isTrue();
        assertThat(screen.withheld("/maven/org/example/x/1/x-1.jar", store)).as("a real release path serves").isFalse();
    }

    @Test
    void a_gate_quarantine_during_promotion_keeps_the_artifact_recoverable_and_does_not_seal() throws IOException {
        // The compliance gate's read side (a PublishInterceptor.withheld) can withhold an artifact on promotion. The
        // promotion must honour that: keep the staged copy (recoverable) and refuse to seal, rather than unpublish the
        // staged pointer and seal PROMOTED - which would leave the artifact neither served nor recoverable from staging.
        String held = "/maven/org/example/gated/1.0/gated-1.0.jar";
        PublishInterceptor gate = new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return held.equals(path);   // the gate quarantines this release path on promotion
            }
        };
        StoreStaging gated = new StoreStaging(store, new Publication(store, List.of(gate)));
        gated.stage("s8", held, "held".getBytes(StandardCharsets.UTF_8));

        assertThatIllegalStateException().isThrownBy(() -> gated.promote("s8")).withMessageContaining(held);

        assertThat(gated.state("s8")).as("not sealed - the id stays open for review").isEqualTo(StagingState.OPEN);
        assertThat(gated.staged("s8")).as("the staged copy is retained (recoverable)").containsExactly(held);
    }

    @Test
    void a_concurrent_mutation_is_refused_while_the_single_writer_lock_is_held() throws IOException {
        // stage/promote/drop take a single-writer lease over the id, so a stage cannot land mid-promote and orphan a
        // pointer past the seal. With a live lease held (here written directly, as another node would), each mutation is
        // refused (a 409 the client retries); once the lease is gone the mutation proceeds. The first stage releases by
        // compare-and-set (leaving a lapsed lease object, reclaimed by the next acquirer), so the rival lease is written
        // against the read token rather than create-if-absent.
        staging.stage("s9", "/maven/org/example/a/1/a-1.jar", "a".getBytes(StandardCharsets.UTF_8));
        Object token = store.readVersioned("staging-lock/s9").map(ArtifactStore.Versioned::token).orElse(null);
        store.writeVersioned("staging-lock/s9",
                ("other-node\n" + Instant.now().plus(Duration.ofMinutes(5))).getBytes(StandardCharsets.UTF_8), token);

        assertThatIllegalStateException().isThrownBy(
                () -> staging.stage("s9", "/maven/org/example/a/2/a-2.jar", "a2".getBytes(StandardCharsets.UTF_8)));
        assertThatIllegalStateException().isThrownBy(() -> staging.promote("s9"));
        assertThatIllegalStateException().isThrownBy(() -> staging.drop("s9"));

        store.delete("staging-lock/s9");                        // the holder's lease lapses / releases
        staging.promote("s9");
        assertThat(staging.state("s9")).as("the mutation proceeds once the lock is free").isEqualTo(StagingState.PROMOTED);
    }

    @Test
    void a_mid_set_promotion_failure_leaves_no_artifact_released() throws IOException {
        // Phase 2 is all-or-nothing: if one staged artifact fails to re-publish mid-set, every artifact already
        // released in the same pass is rolled back, so NO path is left resolvable and every staged copy is retained.
        // Before the fix, promote() released each path and dropped its staged pointer inside the loop, so a failure on
        // a later path left the earlier ones released while the promotion threw and never sealed - a half-promoted set.
        String first = "/maven/org/example/a/1.0/a-1.0.jar";
        String second = "/maven/org/example/b/1.0/b-1.0.jar";
        // Fail the release publish of the second artifact (its release pointer link), so promotion aborts mid-set: if
        // the first is processed first it has already released when the second fails, exercising the rollback; either
        // ordering must leave nothing released.
        FailLinkingPath failing = new FailLinkingPath(store, second);
        StoreStaging guarded = new StoreStaging(failing);
        guarded.stage("m1", first, "a".getBytes(StandardCharsets.UTF_8));
        guarded.stage("m1", second, "b".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> guarded.promote("m1")).isInstanceOfAny(IOException.class, IllegalStateException.class);

        assertThat(guarded.state("m1")).as("nothing sealed - the id stays open").isEqualTo(StagingState.OPEN);
        assertThat(guarded.staged("m1")).as("every staged copy retained").containsExactlyInAnyOrder(first, second);
        assertThat(serve(first)).as("no artifact released - a released path was rolled back").isNull();
        assertThat(serve(second)).as("no artifact released").isNull();
    }

    @Test
    void a_promotion_that_loses_its_lease_mid_set_does_not_retract_the_release_it_already_published()
            throws IOException {
        // A node whose lease lapses mid-promotion and is taken over by a rival must STOP without rolling back: it no
        // longer holds the single-writer lease, and rollback is an unconditional unpublish that would delete a release
        // the rival has already committed - silently dropping an artifact from a sealed promotion. Only a genuine
        // failure while STILL holding the lease rolls back (the test above). Here the store steals the lease the instant
        // the first path releases (simulating a rival grabbing the lapsed lease mid-pass); the promotion must abort on
        // the lost lease and leave that already-published release intact rather than retracting it.
        String first = "/maven/org/example/a/1.0/a-1.0.jar";
        String second = "/maven/org/example/b/1.0/b-1.0.jar";
        // The #212 promote-rollback-vs-rival-commit interleaving, over the shared RacingStore harness: the instant the
        // first release pointer links, a rival steals the lapsed lease (a far-future expiry under a rival holder), so
        // the promoting node's next renew sees a rival owner and loses single-writer status mid-set. The steal is the
        // rival's durable write injected AFTER the release lands (it must land first), then the promotion aborts.
        String[] released = {null};
        RacingStore thief = RacingStore.over(store).after("writeVersioned",
                key -> key.startsWith("publish/") && !key.startsWith("publish/staging/"),
                (rival, key) -> {
                    released[0] = key.substring("publish".length());
                    String lockKey = "staging-lock/r1";
                    Object token = rival.readVersioned(lockKey).map(ArtifactStore.Versioned::token).orElse(null);
                    byte[] stolen = ("rival-node\n" + Instant.parse("2099-01-01T00:00:00Z"))
                            .getBytes(StandardCharsets.UTF_8);
                    rival.writeVersioned(lockKey, stolen, token);
                });
        StoreStaging guarded = new StoreStaging(thief);
        guarded.stage("r1", first, "a".getBytes(StandardCharsets.UTF_8));
        guarded.stage("r1", second, "b".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> guarded.promote("r1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lost the single-writer staging lease");

        assertThat(guarded.state("r1")).as("the losing node never seals the id").isEqualTo(StagingState.OPEN);
        assertThat(released[0]).as("a release did land before the lease was stolen").isNotNull();
        assertThat(serve(released[0]))
                .as("the already-published release is NOT retracted on lost lease - the rival now owns it")
                .isNotNull();
    }

    @Test
    void a_handle_failure_after_the_lease_is_lost_mid_pass_skips_rollback_so_the_rivals_sealed_release_survives()
            throws IOException {
        // The losing interleaving. A promotes P1 (released), then enters P2's unbounded
        // format.handle. DURING that handle A's lease lapses and a rival acquires it, re-links the same
        // content-addressed release and SEALS the promotion (marks PROMOTED). A's handle then throws IOException.
        // The general exception catch used to call rollback(released) unconditionally, retracting P1 - the very
        // release the rival just committed - dropping an artifact from a sealed promotion, unrecoverably. The
        // rollback is now lease-fenced: A re-checks ownership, sees the rival owns the lease, and must SKIP the
        // rollback (leaving the rival's committed release intact) while still surfacing the original failure and
        // logging a WARN. Mirrors the LostLeaseException branch's no-rollback discipline, extended to the
        // IOException path. The steal happens INSIDE handle (via store.open of the second blob), AFTER the loop-top
        // renew has already passed - so this is the IOException path, not the LostLeaseException path.
        CapturingLoggerFinder.WARNINGS.clear();
        String p1 = "/maven/org/example/a/1.0/a-1.0.jar";
        String p2 = "/maven/org/example/b/1.0/b-1.0.jar";
        String[] firstReleased = {null};
        RacingStore failing = failingSecondHandle(store, "f1", true, p1, p2, firstReleased);
        StoreStaging guarded = new StoreStaging(failing);
        guarded.stage("f1", p1, "a".getBytes(StandardCharsets.UTF_8));
        guarded.stage("f1", p2, "b".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> guarded.promote("f1"))
                .as("the original handle failure is surfaced, not swallowed")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injected slow-handle failure");

        assertThat(firstReleased[0]).as("a release did land before the lease was lost").isNotNull();
        assertThat(serve(firstReleased[0]))
                .as("the rival's committed+sealed release is NOT retracted - the artifact is not lost")
                .isNotNull();
        assertThat(guarded.state("f1")).as("the rival sealed PROMOTED; the losing node did not clobber the seal")
                .isEqualTo(StagingState.PROMOTED);
        List<String> warned = CapturingLoggerFinder.WARNINGS.stream()
                .filter(line -> line.startsWith(StoreStaging.class.getName() + "|"))
                .filter(line -> line.contains("skipping rollback"))
                .toList();
        assertThat(warned).as("the skipped rollback is surfaced as a WARN naming the id, not done silently")
                .isNotEmpty();
        assertThat(warned.getFirst()).contains("rival owns the promotion of f1");
    }

    @Test
    void a_handle_failure_while_the_lease_is_still_owned_rolls_back_exactly_as_before() throws IOException {
        // The control / no-regression counterpart: identical injected handle failure on the second path, but the
        // lease is NOT stolen - this node still provably owns it at the rollback re-check. The lease-fence must then
        // roll back exactly as before: the already-released first path is retracted, nothing is left released, the id
        // stays OPEN, and no "skipped rollback" WARN is logged. Only the difference in lease ownership - not the
        // failure itself - changes the behaviour, isolating the fix.
        CapturingLoggerFinder.WARNINGS.clear();
        String p1 = "/maven/org/example/c/1.0/c-1.0.jar";
        String p2 = "/maven/org/example/d/1.0/d-1.0.jar";
        String[] firstReleased = {null};
        RacingStore failing = failingSecondHandle(store, "f2", false, p1, p2, firstReleased);
        StoreStaging guarded = new StoreStaging(failing);
        guarded.stage("f2", p1, "c".getBytes(StandardCharsets.UTF_8));
        guarded.stage("f2", p2, "d".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> guarded.promote("f2"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injected slow-handle failure");

        assertThat(firstReleased[0]).as("a release did land before the failure").isNotNull();
        assertThat(serve(firstReleased[0]))
                .as("still the single writer - the release this failed pass created is rolled back")
                .isNull();
        assertThat(serve(p1)).as("no artifact left released").isNull();
        assertThat(serve(p2)).as("no artifact left released").isNull();
        assertThat(guarded.state("f2")).as("nothing sealed - the id stays open").isEqualTo(StagingState.OPEN);
        assertThat(guarded.staged("f2")).as("every staged copy retained").containsExactlyInAnyOrder(p1, p2);
        assertThat(CapturingLoggerFinder.WARNINGS.stream()
                .filter(line -> line.startsWith(StoreStaging.class.getName() + "|"))
                .anyMatch(line -> line.contains("skipping rollback")))
                .as("an owned-node rollback does not log the lost-lease skip").isFalse();
    }

    @Test
    void the_promotion_lease_is_renewable_and_cannot_be_stolen_while_renewed() throws IOException {
        // The lease is renewable and fenced: a live holder that keeps renewing cannot be stolen even past the original
        // ttl, so a long promotion is never interrupted by a second promoter; only once it stops renewing and the
        // (renewed) lease lapses may a rival reclaim it, and the original holder's renew then refuses - it lost
        // single-writer status. The non-renewable lock this replaced let a promotion outlive its ttl and be stolen.
        Duration ttl = Duration.ofSeconds(30);
        Lease lock = new Lease(store, StoreStaging.LOCKS, ttl);
        Instant t0 = Instant.parse("2026-07-25T00:00:00Z");
        assertThat(lock.acquire("p", "A", t0)).isTrue();
        assertThat(lock.acquire("p", "B", t0.plusSeconds(10))).as("a live lease refuses a rival").isFalse();
        assertThat(lock.renew("p", "A", t0.plusSeconds(20))).as("the holder renews its live lease").isTrue();
        assertThat(lock.acquire("p", "B", t0.plusSeconds(40)))
                .as("a renewed lease is not stealable even past the original ttl").isFalse();
        assertThat(lock.acquire("p", "B", t0.plusSeconds(65))).as("once renewal stops, the lapsed lease is reclaimable")
                .isTrue();
        assertThat(lock.renew("p", "A", t0.plusSeconds(66)))
                .as("the old holder's renew refuses once the lease was legitimately taken over").isFalse();
    }

    @Test
    void releasing_a_lapsed_lease_never_clobbers_the_rival_that_took_it() throws IOException {
        // The read-check-delete release this replaced could delete a rival's freshly stolen lock in the window between
        // the owner check and the delete, letting a second writer in. The compare-and-set release sees the rival as
        // owner and leaves its lease untouched.
        Duration ttl = Duration.ofSeconds(30);
        Lease lock = new Lease(store, StoreStaging.LOCKS, ttl);
        Instant t0 = Instant.parse("2026-07-25T00:00:00Z");
        assertThat(lock.acquire("q", "A", t0)).isTrue();
        assertThat(lock.acquire("q", "B", t0.plusSeconds(31))).as("A's lease lapsed - B legitimately steals it")
                .isTrue();
        lock.release("q", "A", t0.plusSeconds(32));   // A's stale release must not delete B's lock
        assertThat(lock.acquire("q", "C", t0.plusSeconds(33))).as("B still holds the lease - a third actor is refused")
                .isFalse();
        lock.release("q", "B", t0.plusSeconds(34));   // B releases its own lease by compare-and-set
        assertThat(lock.acquire("q", "C", t0.plusSeconds(35))).as("B's own release frees it at once").isTrue();
    }

    @Test
    void a_percent_encoded_or_dotdot_staged_path_is_rejected() throws IOException {
        // A bare contains("..") missed a percent-encoded ("%2e%2e"/"%2E%2E"), an encoded-slash ("..%2f") or a
        // double-encoded ("%252e%252e") traversal; the guard now decodes before the check and refuses any residual
        // percent-escape, so every variant is rejected while a plain ".." stays rejected as before.
        for (String bad : List.of(
                "/maven/org/../evil/1.0/x-1.0.jar",
                "/maven/%2e%2e/evil/x-1.0.jar",
                "/maven/%2E%2E/evil/x-1.0.jar",
                "/maven/..%2fevil/x-1.0.jar",
                "/maven/%252e%252e/evil/x-1.0.jar")) {
            assertThatIllegalArgumentException().as("rejected: %s", bad).isThrownBy(
                    () -> staging.stage("trav", bad, "x".getBytes(StandardCharsets.UTF_8)));
        }
        // A normal artifact coordinate still stages fine - the guard rejects only unsafe paths.
        assertThatCode(() -> staging.stage("trav-ok", "/maven/org/example/ok/1.0/ok-1.0.jar",
                "ok".getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
    }

    private byte[] serve(String path) throws IOException {
        Optional<String> key = new Publication(store).located(path);
        if (key.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key.get(), out);
        return out.toByteArray();
    }

    /** A store decorator that, once {@link #arm armed}, fails the very next {@code writeVersioned} to a
     *  {@code staging-state/} marker key once - the concurrent CAS conflict the seal's retry must absorb - then
     *  delegates untouched. */
    private static final class ConflictOnceOnStateStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private boolean armed;
        private boolean always;
        private int stateConflicts;

        private ConflictOnceOnStateStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        private void arm() {
            armed = true;
        }

        /** Fail EVERY {@code staging-state/} write, so the seal can never land - the persistently-contended id that
         *  exhausts the retry and must surface as a fail-closed throw rather than a silent open-and-re-promotable seal. */
        private void armAlways() {
            always = true;
        }

        @Override
        public synchronized boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (key.startsWith("staging-state/") && (always || armed)) {
                armed = false;
                stateConflicts++;
                return false;
            }
            return delegate.writeVersioned(key, content, expected);
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
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** A store decorator that throws when the release pointer for one chosen release path is linked - the mid-set
     *  re-publish failure promotion's rollback must absorb (the failing artifact never releases; any sibling already
     *  released in the same pass is rolled back) - and delegates everything else untouched. */
    private static final class FailLinkingPath implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private final String failKey;

        private FailLinkingPath(ArtifactStore delegate, String failReleasePath) {
            this.delegate = delegate;
            this.failKey = "publish" + failReleasePath;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (key.equals(failKey)) {
                throw new IOException("injected release-link failure for " + key);
            }
            return delegate.writeVersioned(key, content, expected);
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
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    /** Build a {@link RacingStore} reproducing the losing interleaving: the FIRST staged release
     *  link records {@code firstReleased}, then the SECOND path's slow {@code format.handle} fails - and, when
     *  {@code stealLeaseAndSeal}, only after a rival has acquired the lapsed lease and SEALED the promotion, so the
     *  losing node's rollback (if it ran unfenced) would retract the rival's committed release. Keying on "the second
     *  staged release-link write" is order-independent: whichever path promotes second is the one that fails, and the
     *  first has provably released by then. This is the shared-harness form of the hand-rolled {@code FailSecondHandle}
     *  double it replaces. */
    private static RacingStore failingSecondHandle(ArtifactStore store, String id, boolean stealLeaseAndSeal,
                                                   String p1, String p2, String[] firstReleased) {
        Set<String> stagedReleasePaths = Set.of(p1, p2);
        Predicate<String> stagedReleaseLink = key -> key.startsWith("publish/")
                && stagedReleasePaths.contains(key.substring("publish".length()));
        return RacingStore.over(store)
                .after("writeVersioned", stagedReleaseLink,
                        (rival, key) -> firstReleased[0] = key.substring("publish".length()))
                .insteadFail("writeVersioned",
                        key -> stagedReleaseLink.test(key) && firstReleased[0] != null,
                        (rival, key) -> {
                            if (stealLeaseAndSeal) {
                                // The lease lapsed during this (slow, unbounded) handle: a rival acquires the lapsed
                                // lease and SEALS the promotion (staging-state -> PROMOTED) before the handle throws.
                                String lockKey = "staging-lock/" + id;
                                Object lockToken = rival.readVersioned(lockKey)
                                        .map(ArtifactStore.Versioned::token).orElse(null);
                                rival.writeVersioned(lockKey,
                                        ("rival-node\n" + Instant.parse("2099-01-01T00:00:00Z"))
                                                .getBytes(StandardCharsets.UTF_8), lockToken);
                                Object stateToken = rival.readVersioned("staging-state/" + id)
                                        .map(ArtifactStore.Versioned::token).orElse(null);
                                rival.writeVersioned("staging-state/" + id,
                                        ("PROMOTED " + Instant.now()).getBytes(StandardCharsets.UTF_8), stateToken);
                            }
                        },
                        "injected slow-handle failure on the second promoted path of " + id);
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }
}
