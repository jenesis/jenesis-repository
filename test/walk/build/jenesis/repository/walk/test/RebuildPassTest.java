package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkConsumer.Family;
import build.jenesis.repository.walk.WalkConsumer.Walked;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared rebuild pass - the walk half of the two-route derived-metadata contract made runnable: one enumeration
 * of the pointer roots feeds every {@link WalkConsumer} with retained-artifact notifications. Exactly-once delivery
 * per pass with the documented descriptor richness (request path under {@code publish/}, raw store key elsewhere,
 * hash always, blob size or {@code -1} for a torn pointer); at-least-once across an injected crash-resume with
 * idempotency absorbing the replay; a consumer enabled late rebuilding its whole view purely from the walk; the
 * pass hooks bracketing delivery even over an empty store; and the guard rails (reserved roots refused, no
 * consumers means nothing enumerated).
 */
class RebuildPassTest {

    private static final int CHECKPOINT = 5;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? scoped.toString() : null);
    }

    private StoreArtifactWalk walk() {
        return new StoreArtifactWalk(CHECKPOINT, 1, Duration.ofMinutes(10), clock);
    }

    /** Store real content and point {@code publish<path>} at it, the way {@code Publication} lays pointers out. */
    private static String publish(ArtifactStore store, String path, String content) throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        store.writeVersioned("publish" + path, hash.getBytes(StandardCharsets.UTF_8), null);
        return hash;
    }

    /** A consumer recording everything it is handed: the event order, and an idempotent path-to-hash view.
     *  Not final: a leg below extends it to fail once part way through and then behave. */
    private static class Recording implements WalkConsumer {

        final List<String> events = new ArrayList<>();
        final List<ArtifactDescriptor> retained = new ArrayList<>();
        final Map<String, String> derived = new HashMap<>();
        final Map<String, String> walked = new HashMap<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
            events.add("retained:" + artifact.path());
            retained.add(artifact);
            derived.put(artifact.path(), artifact.hash());
        }

        @Override
        public void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) {
            events.add("withheld:" + artifact.path());
        }

        @Override
        public void onWalked(Walked entry, ArtifactStore store) throws IOException {
            events.add("walked:" + entry);
            walked.put(entry.key(), entry.body().map(String::new).orElse(""));
        }

        @Override
        public void onPassStarted(WalkPass pass) {
            events.add("started:" + pass.generation());
        }

        @Override
        public void onPassCompleted(WalkPass pass) {
            events.add("completed:" + pass.generation());
        }
    }

    @Test
    void a_pass_delivers_every_pointer_exactly_once_to_every_consumer_between_the_hooks() throws IOException {
        ArtifactStore store = store("exactly-once");
        String first = publish(store, "/maven/app-1.0.jar", "first payload");
        String second = publish(store, "/maven/app-1.1.jar", "second one");
        // Sidecar-shaped leaves must never be delivered: not a bare hash, or far too large to be a pointer.
        store.writeVersioned("publish/maven/notes", "2026-07-16T00:00:00Z false".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("publish/maven/oversized", new byte[2048], null);
        Recording one = new Recording(), two = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(one, two));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        for (Recording consumer : List.of(one, two)) {
            assertThat(consumer.events).as("started brackets the first delivery, completed the last")
                    .startsWith("started:1").endsWith("completed:1");
            assertThat(consumer.derived).containsOnlyKeys("/maven/app-1.0.jar", "/maven/app-1.1.jar");
            assertThat(consumer.derived).containsEntry("/maven/app-1.0.jar", first)
                    .containsEntry("/maven/app-1.1.jar", second);
            assertThat(consumer.retained).as("exactly once per pointer, no sidecar or oversized leaf").hasSize(2);
            assertThat(consumer.retained.getFirst().size()).isEqualTo("first payload".length());
            assertThat(consumer.retained.getFirst().path()).as("the publish/ namespace maps to the request path")
                    .isEqualTo("/maven/app-1.0.jar");
        }
    }

    @Test
    void a_crash_mid_pass_resumes_and_idempotency_absorbs_the_replayed_stride() throws IOException {
        ArtifactStore store = store("crash");
        Map<String, String> expected = new HashMap<>();
        for (char letter = 'a'; letter <= 'z'; letter++) {
            expected.put("/" + letter + "/artifact", publish(store, "/" + letter + "/artifact", "content " + letter));
        }
        Recording consumer = new Recording();
        // The crash is the store going away under the pass's own pointer read, thirteen deliveries in - not a
        // consumer throwing, which fails that consumer alone and lets the pass complete for the others.
        FaultInjectingStore crashing = FaultInjectingStore.wrap(store);
        Recording arming = new Recording() {
            @Override
            public String name() {
                return "arming";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
                super.onRetained(artifact, store);
                if (retained.size() == 13) {
                    crashing.failNextOn(FaultInjectingStore.Op.READ_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
                }
            }
        };
        assertThatThrownBy(() -> RebuildPass.run(walk(), crashing, List.of("publish"), List.of(consumer, arming)))
                .as("a failure of the walk's own read propagates and leaves the pass active")
                .isInstanceOf(IOException.class);
        clock.advance(Duration.ofMinutes(11));

        Optional<WalkPass> resumed = RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer));

        assertThat(resumed).hasValueSatisfying(pass -> {
            assertThat(pass.complete()).isTrue();
            assertThat(pass.generation()).as("a resume joins the pass, never restarts a new one").isEqualTo(1);
        });
        assertThat(consumer.derived).as("at-least-once across the crash, idempotent upsert converging on the truth")
                .containsExactlyInAnyOrderEntriesOf(expected);
        Set<String> paths = new HashSet<>();
        for (ArtifactDescriptor artifact : consumer.retained) {
            paths.add(artifact.path());
        }
        assertThat(paths).as("no pointer is ever missed").hasSize(expected.size());
        assertThat(consumer.retained.size() - expected.size()).as("at most one checkpoint stride is replayed")
                .isLessThanOrEqualTo(CHECKPOINT);
    }

    @Test
    void a_consumer_enabled_late_rebuilds_its_whole_view_purely_from_the_walk() throws IOException {
        ArtifactStore store = store("late");
        // The history happened long before the plugin existed: artifacts published, one of them removed again.
        Map<String, String> expected = new HashMap<>();
        expected.put("/npm/left-pad-1.0.tgz", publish(store, "/npm/left-pad-1.0.tgz", "left pad"));
        expected.put("/pypi/requests-2.0.whl", publish(store, "/pypi/requests-2.0.whl", "requests"));
        publish(store, "/npm/gone-0.1.tgz", "removed again");
        store.delete("publish/npm/gone-0.1.tgz");
        Recording late = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(late));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(late.derived).as("the walk alone rebuilds the full view - and only of what is still retained")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void an_empty_store_still_fires_started_then_completed() throws IOException {
        ArtifactStore store = store("empty");
        Recording consumer = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(consumer.events).as("a rebuild from an empty truth is still a rebuild: reset, then commit empty")
                .containsExactly("started:1", "completed:1");
    }

    @Test
    void a_blobs_namespace_root_delivers_the_raw_key_and_a_torn_pointer_a_negative_size() throws IOException {
        ArtifactStore store = store("raw-keys");
        String served = publish(store, "/kept", "kept bytes");
        store.writeVersioned("npm/lodash/-/lodash-4.17.21.tgz", served.getBytes(StandardCharsets.UTF_8), null);
        String missing = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        store.writeVersioned("npm/lodash/-/lodash-4.17.20.tgz", missing.getBytes(StandardCharsets.UTF_8), null);
        Recording consumer = new Recording();

        RebuildPass.run(walk(), store, List.of("publish", "npm"), List.of(consumer));

        assertThat(consumer.derived).containsOnlyKeys(
                "/kept", "npm/lodash/-/lodash-4.17.21.tgz", "npm/lodash/-/lodash-4.17.20.tgz");
        ArtifactDescriptor torn = consumer.retained.stream()
                .filter(artifact -> artifact.path().equals("npm/lodash/-/lodash-4.17.20.tgz"))
                .findFirst().orElseThrow();
        assertThat(torn.hash()).isEqualTo(missing);
        assertThat(torn.size()).as("a pointer whose blob is missing is delivered as the torn state it is")
                .isNegative();
        ArtifactDescriptor linked = consumer.retained.stream()
                .filter(artifact -> artifact.path().equals("npm/lodash/-/lodash-4.17.21.tgz"))
                .findFirst().orElseThrow();
        assertThat(linked.size()).isEqualTo("kept bytes".length());
    }

    @Test
    void a_rebuild_never_reinstates_a_withheld_or_quarantined_pointer_into_the_index() throws IOException {
        ArtifactStore store = store("withheld");
        // A served artifact, an artifact retracted after the fact (a fresh advisory against bytes that served for
        // months - pointer and blob both intact), and a pointer the gate diverted to the quarantine review subtree.
        String served = publish(store, "/maven/app-1.0.jar", "served payload");
        publish(store, "/maven/app-1.1.jar", "later flagged");
        publish(store, "/quarantine/maven/held-1.0.jar", "held for review");
        // The withhold screen the deployment runs: app-1.1 has been retracted from serving though its pointer stands.
        Publication screened = new Publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals("/maven/app-1.1.jar");
            }
        }));
        Recording consumer = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, screened, List.of("publish"), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(consumer.derived).as("a rebuild yields exactly what a GET would - never a withheld or "
                        + "quarantine-review pointer, so neither reappears in an index the pass rebuilds")
                .containsOnlyKeys("/maven/app-1.0.jar")
                .containsEntry("/maven/app-1.0.jar", served);
        assertThat(consumer.derived).as("the retracted-after-advisory artifact is not reinstated")
                .doesNotContainKey("/maven/app-1.1.jar");
        assertThat(consumer.derived).as("no phantom index entry for a quarantine-review pointer")
                .doesNotContainKey("/quarantine/maven/held-1.0.jar");
    }

    @Test
    void a_withheld_and_gc_reclaimed_pointer_is_skipped_not_delivered_as_torn() throws IOException {
        ArtifactStore store = store("withheld-and-gone");
        String served = publish(store, "/maven/app-1.0.jar", "served payload");
        // A withheld artifact whose blob a later garbage collection ALSO reclaimed: the pointer stands, its blob is
        // gone, and a retraction interceptor withholds the path. The former hand-rolled discrimination
        // (located().isEmpty() && store.exists("blobs/" + named)) mis-classified this as a merely-torn pointer - the
        // absent blob flipped the && to false, so it read "not withheld" and DELIVERED it, reinstating a withheld
        // artifact into every rebuilt index. Through the seam the withhold probe runs first, so the state is WITHHELD
        // and the pointer is correctly skipped - the one intended behaviour change of this migration.
        String gone = publish(store, "/maven/app-1.1.jar", "withheld then reclaimed");
        store.delete("blobs/" + gone);
        Publication screened = new Publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals("/maven/app-1.1.jar");
            }
        }));
        Recording consumer = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, screened, List.of("publish"), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(consumer.derived).as("a withheld-and-reclaimed pointer is withheld, not torn - never delivered")
                .containsOnlyKeys("/maven/app-1.0.jar")
                .containsEntry("/maven/app-1.0.jar", served);
        assertThat(consumer.derived).as("the withheld-and-gone artifact is not reinstated as a torn pointer")
                .doesNotContainKey("/maven/app-1.1.jar");
    }

    @Test
    void reserved_roots_are_refused_and_no_consumer_means_nothing_is_enumerated() throws IOException {
        ArtifactStore store = store("guards");
        publish(store, "/artifact", "bytes");
        for (String reserved : List.of("blobs", "gc", "walks", " ")) {
            assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of(reserved), List.of(new Recording())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of(), List.of(new Recording())))
                .isInstanceOf(IllegalArgumentException.class);

        StoreArtifactWalk walk = walk();
        assertThat(RebuildPass.run(walk, store, List.of("publish"), List.of())).isEmpty();
        assertThat(walk.pass(store, RebuildPass.CONSUMER)).as("no consumer, no pass state touched").isEmpty();
    }

    /** A consumer listening on the families it is given, seeing withheld pointers if told to. */
    private static final class Listener extends Recording {

        private final Set<Family> families;
        private final boolean seesWithheld;

        private Listener(boolean seesWithheld, Family... families) {
            this.families = Set.of(families);
            this.seesWithheld = seesWithheld;
        }

        @Override
        public Set<Family> families() {
            return families;
        }

        @Override
        public boolean seesWithheld() {
            return seesWithheld;
        }
    }

    @Test
    void a_family_is_enumerated_once_for_every_consumer_listening_on_it_and_not_at_all_otherwise() throws IOException {
        ArtifactStore store = store("families");
        String hash = publish(store, "/npm/left-pad-1.0.tgz", "left pad");
        store.writeVersioned("published/npm/left-pad/1.0", "row".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("pinned/npm/left-pad/1.0", "".getBytes(StandardCharsets.UTF_8), null);
        Listener pointers = new Listener(false, Family.POINTERS);
        Listener rows = new Listener(false, Family.INVENTORY);
        Listener pool = new Listener(false, Family.BLOBS);
        RebuildPass.Roots roots = new RebuildPass.Roots(List.of("publish"), List.of("published"), List.of("blobs"),
                List.of("pinned"));

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, new Publication(store), roots,
                List.of(pointers, rows, pool));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.roots())
                .as("the pass walked exactly the families somebody listens on: not the derived rows")
                .containsExactly("blobs", "publish", "published"));
        assertThat(pointers.derived).containsOnlyKeys("/npm/left-pad-1.0.tgz");
        assertThat(pointers.walked).as("a pointer consumer is handed no other family").isEmpty();
        assertThat(rows.walked).as("the inventory row, with its body read once for whoever asks")
                .containsExactly(Map.entry("published/npm/left-pad/1.0", "row"));
        assertThat(rows.derived).as("an inventory consumer is handed no pointer").isEmpty();
        assertThat(pool.walked).containsOnlyKeys("blobs/" + hash);
    }

    @Test
    void a_completed_pass_records_what_it_delivered_per_family() throws IOException {
        ArtifactStore store = store("measured");
        publish(store, "/npm/left-pad-1.0.tgz", "left pad");
        publish(store, "/npm/right-pad-1.0.tgz", "right pad");
        store.writeVersioned("published/npm/left-pad/1.0", "row".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("pinned/npm/left-pad/1.0", "".getBytes(StandardCharsets.UTF_8), null);
        RebuildPass.Roots roots = new RebuildPass.Roots(List.of("publish"), List.of("published"), List.of("blobs"),
                List.of("pinned"));
        assertThat(RebuildPass.last(store)).as("no pass has completed").isEmpty();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, new Publication(store), roots,
                List.of(new Listener(false, Family.POINTERS), new Listener(false, Family.INVENTORY),
                        new Listener(false, Family.BLOBS)));

        assertThat(RebuildPass.last(store)).hasValueSatisfying(measured -> {
            assertThat(measured.generation()).isEqualTo(pass.orElseThrow().generation());
            assertThat(measured.started()).isEqualTo(pass.orElseThrow().started());
            assertThat(measured.completed()).isAfterOrEqualTo(measured.started());
            assertThat(measured.pointers()).as("two served pointers").isEqualTo(2);
            assertThat(measured.inventory()).as("one row").isEqualTo(1);
            assertThat(measured.blobs()).as("two blobs").isEqualTo(2);
            assertThat(measured.derived()).as("nobody listened on the derived rows, so they were not walked")
                    .isZero();
            assertThat(measured.objects()).isEqualTo(5);
        });
        assertThat(store.list("walks/" + RebuildPass.CONSUMER + "/counted"))
                .as("the generation's counters are folded into the account and gone").isEmpty();
    }

    @Test
    void a_withheld_pointer_reaches_only_a_consumer_that_asked_to_see_it() throws IOException {
        ArtifactStore store = store("withheld");
        publish(store, "/npm/served-1.0.tgz", "served");
        String held = publish(store, "/npm/held-1.0.tgz", "held back");
        build.jenesis.repository.store.Withheld.mark(store, held);
        Listener asking = new Listener(true, Family.POINTERS);
        Recording blind = new Recording();

        RebuildPass.run(walk(), store, List.of("publish"), List.of(asking, blind));

        assertThat(asking.derived).as("retained pointers reach both").containsOnlyKeys("/npm/served-1.0.tgz");
        assertThat(asking.events).contains("withheld:/npm/held-1.0.tgz");
        assertThat(blind.derived).containsOnlyKeys("/npm/served-1.0.tgz");
        assertThat(blind.events).as("a consumer that did not ask never learns a withheld pointer exists")
                .noneMatch(event -> event.contains("held-1.0"));
    }

    @Test
    void a_pass_with_no_root_for_any_listened_family_is_refused() {
        ArtifactStore store = store("rootless");
        Listener pool = new Listener(false, Family.BLOBS);

        assertThatThrownBy(() -> RebuildPass.run(walk(), store, new Publication(store),
                RebuildPass.Roots.pointers(List.of("publish")), List.of(pool)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no root to walk");
    }

    @Test
    void a_consumer_that_throws_fails_alone_is_recorded_and_is_redelivered_by_the_next_generation() throws IOException {
        ArtifactStore store = store("alone");
        Map<String, String> expected = new HashMap<>();
        for (int each = 0; each < 3; each++) {
            expected.put("/npm/pkg-" + each + ".tgz", publish(store, "/npm/pkg-" + each + ".tgz", "bytes " + each));
        }
        Recording steady = new Recording();
        Recording broken = new Recording() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
                super.onRetained(artifact, store);
                if (retained.size() == 2) {
                    throw new IllegalStateException("second delivery refused");
                }
            }
        };

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(broken, steady));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(steady.derived).as("the other consumer converges").containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(broken.retained).as("the failing consumer is handed nothing after the delivery it failed on")
                .hasSize(2);
        assertThat(broken.events).as("nor the completion hook").noneMatch(event -> event.startsWith("completed"));
        assertThat(RebuildPass.failed(store)).singleElement().satisfies(failed -> {
            assertThat(failed.consumer()).isEqualTo("broken");
            assertThat(failed.generation()).isEqualTo(pass.orElseThrow().generation());
            assertThat(failed.key()).startsWith("publish/npm/pkg-");
            assertThat(failed.failure()).contains("second delivery refused");
        });

        Recording mended = new Recording() {
            @Override
            public String name() {
                return "broken";
            }
        };
        RebuildPass.run(walk(), store, List.of("publish"), List.of(mended, steady));

        assertThat(mended.derived).as("the next generation redelivers everything to it")
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(RebuildPass.failed(store)).as("and the record of the earlier generation's failure goes").isEmpty();
    }
}
