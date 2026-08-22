package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

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
        List<String> before = new ArrayList<>();
        WalkConsumer fatal = new WalkConsumer() {
            @Override
            public String name() {
                return "fatal";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                before.add(artifact.path());
                if (before.size() == 13) {
                    throw new IOException("crash mid-pass");
                }
            }
        };
        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer, fatal)))
                .hasMessageContaining("crash mid-pass");
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

    /**
     * A consumer that fails mid-pass is re-delivered every item it missed, because the cursor never moved past it.
     *
     * <p>This is the property that makes propagating the right choice rather than a missing containment, and it is
     * the one a reviewer would break. The cursor is <em>shared</em> - one walk, one committed position, N consumers
     * - so containing consumer A's failure while the stride commits would advance past items A never received,
     * permanently, and A would then report itself converged: the silently-incomplete view §5 forbids.
     *
     * <p>The sibling crash leg above pins that the failure propagates at all, by expecting a throw. It does not pin
     * the consequence: it resumes with only the surviving consumer, so it never asks whether the <em>failing</em>
     * one is made whole afterwards. This leg asks exactly that.
     *
     * <p>It asserts the resumed generation as well as the delivery, and that pairing is load-bearing rather than
     * decorative. Measured against a planted containment that swallows during the walk and rethrows once the pass
     * is over - the shape that keeps a throw and still advances the cursor - the delivery assertion alone passes,
     * because a completed pass makes the next run a fresh generation that re-walks everything and hands the failed
     * consumer its items after all. The generation assertion is what sees the cursor moved.
     */
    @Test
    void a_consumer_that_failed_mid_pass_is_re_delivered_everything_it_missed() throws IOException {
        ArtifactStore store = store("redelivery");
        Map<String, String> expected = new HashMap<>();
        for (char letter = 'a'; letter <= 'z'; letter++) {
            expected.put("/" + letter + "/artifact", publish(store, "/" + letter + "/artifact", "content " + letter));
        }
        Recording healthy = new Recording();
        // Fails once, part way through, then behaves - the plugin that was briefly broken and is now fixed.
        boolean[] alreadyFailed = {false};
        Recording flaky = new Recording() {
            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
                if (!alreadyFailed[0] && derived.size() == 13) {
                    alreadyFailed[0] = true;
                    // Unchecked, because Recording's own onRetained declares no IOException - and it drives the
                    // runtime arm of the fan-out, where the sibling crash leg drives the checked one. One-shot: the
                    // plugin that was briefly broken and is fixed by the time the pass resumes.
                    throw new UncheckedIOException(new IOException("consumer gave way mid-pass"));
                }
                super.onRetained(artifact, store);
            }
        };

        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of("publish"), List.of(healthy, flaky)))
                .as("the failure is not contained: it reaches the pass, which is what holds the cursor")
                .hasMessageContaining("consumer gave way mid-pass");
        int seenBeforeFailing = flaky.derived.size();
        assertThat(seenBeforeFailing).as("it really did stop part way").isLessThan(expected.size());

        clock.advance(Duration.ofMinutes(11));
        Optional<WalkPass> resumed = RebuildPass.run(walk(), store, List.of("publish"), List.of(healthy, flaky));

        assertThat(resumed).hasValueSatisfying(pass -> assertThat(pass.generation())
                .as("the resume JOINS the interrupted pass rather than starting a fresh one - which is the half "
                        + "that fails if the failure was contained and the pass therefore ran to completion")
                .isEqualTo(1));
        assertThat(flaky.derived)
                .as("the consumer that failed is whole afterwards - the cursor never advanced past what it missed")
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(healthy.derived)
                .as("and the consumer that did not fail is whole too")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** A propagating failure says which consumer produced it. Propagating is right, but it left the operator a
     *  stack frame and no name: with a dozen consumers installed that is the difference between a name and a
     *  bisect. The exception itself is untouched - a caller still distinguishes an IOException from a runtime one
     *  - and the attribution rides as a suppressed marker. */
    @Test
    void a_propagating_failure_names_the_consumer_that_produced_it() throws IOException {
        ArtifactStore store = store("attribution");
        publish(store, "/a/artifact", "content a");
        WalkConsumer broken = new WalkConsumer() {
            @Override
            public String name() {
                return "the-broken-one";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                throw new IOException("plugin gave way");
            }
        };

        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of("publish"), List.of(broken)))
                .isInstanceOf(IOException.class)
                .as("the consumer's own exception reaches the caller unchanged in type and message")
                .hasMessageContaining("plugin gave way")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .as("and carries the name of the consumer that produced it")
                        .anySatisfy(marker -> assertThat(marker.getMessage())
                                .contains("the-broken-one").contains(broken.getClass().getName())));
    }
}
