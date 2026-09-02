package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pointer-last accepted-layout commit ({@link Publication#commit}) - the one hosted-publish choreography every
 * ingress edge runs. The suite pins the order it promises (store &rarr; screen once &rarr; gate the republish &rarr;
 * lay out sidecars &rarr; link the declared visibility &rarr; notify once), the republish policy supplied as data, and
 * - through {@link FaultInjectingStore} - what a crash at <em>each</em> step of that order leaves behind.
 *
 * <p>The durability claim under test is deliberately the weak one the commit protocol actually proves: nothing serves
 * before the declared visibility write, and the after-commit observers are best-effort across the
 * visibility-to-callback window. Every crash leg therefore asserts two things - what is servable, and whether the
 * observer was notified - and then that a byte-identical replay converges onto the same store state and completes the
 * publish. A replay is not merely "does not fail": the whole store is snapshotted and compared byte for byte.
 */
class PublicationCommitTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ArtifactDescriptor descriptor(String path) {
        return ArtifactDescriptor.at("raw", path);
    }

    /** Records what it was told about a publish, and - the load-bearing bit - whether the artifact was already
     *  servable at the moment it was told, which is what "pointer-last, then notify" has to mean. */
    private static final class Recorder implements PublicationObserver {

        private final List<ArtifactDescriptor> published = new ArrayList<>();
        private final List<Boolean> servableWhenNotified = new ArrayList<>();
        private final String servedPath;
        private boolean throwing;

        private Recorder(String servedPath) {
            this.servedPath = servedPath;
        }

        @Override
        public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
            published.add(artifact);
            servableWhenNotified.add(new Publication(store).located(servedPath).isPresent());
            if (throwing) {
                throw new IOException("an after-commit observer failed");
            }
        }
    }

    /** An interceptor with a fixed verdict that counts how often the chain ran over a body. */
    private static final class Counting implements PublishInterceptor {

        private final Disposition verdict;
        private int assessed;

        private Counting(Disposition verdict) {
            this.verdict = verdict;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) {
            assessed++;
            return verdict;
        }
    }

    /** Every stored object as key &rarr; content digest, so a replay can be proven byte-identical rather than merely
     *  green. */
    private Map<String, String> snapshot() throws IOException {
        return objects(root);
    }

    // --- the choreography ------------------------------------------------------------------------------------------

    @Test
    void the_accepted_layout_runs_sidecars_first_the_pointer_last_and_the_observer_after() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Counting screen = new Counting(PublishInterceptor.Disposition.ACCEPT);
        Publication publication = new Publication(store, List.of(screen), List.of(observer));
        List<String> order = new ArrayList<>();

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), accepted -> {
                    order.add("layout");
                    assertThat(new Publication(accepted.store()).located("/raw/a"))
                            .as("nothing serves while the layout is still writing its sidecars").isEmpty();
                    accepted.sidecar("side/raw/a.meta", ("size=" + accepted.size()).getBytes(StandardCharsets.UTF_8));
                    return Publication.Visibility.at("/raw/a");
                });

        assertThat(screen.assessed).as("exactly one screen per hosted publish").isEqualTo(1);
        assertThat(order).containsExactly("layout");
        assertThat(commit.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(commit.visible()).isTrue();
        assertThat(commit.artifact().hash()).isEqualTo(commit.hash());
        assertThat(commit.artifact().size()).isEqualTo("payload".length());
        assertThat(store.exists("side/raw/a.meta")).isTrue();
        assertThat(publication.located("/raw/a")).contains("blobs/" + commit.hash());
        assertThat(observer.published).hasSize(1);
        assertThat(observer.servableWhenNotified)
                .as("the observer is notified only once visibility has committed").containsExactly(true);
        new StoreInvariants(store).assertConsistent();
    }

    @Test
    void a_layout_reads_the_accepted_body_back_as_a_restream() throws IOException {
        Publication publication = new Publication(store, List.of());
        List<String> seen = new ArrayList<>();

        publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), accepted -> {
            try (InputStream first = accepted.open(); InputStream second = accepted.open()) {
                seen.add(new String(first.readAllBytes(), StandardCharsets.UTF_8));
                seen.add(new String(second.readAllBytes(), StandardCharsets.UTF_8));
            }
            return Publication.Visibility.at("/raw/a");
        });

        assertThat(seen).as("each open() is a fresh stream over the stored blob, never a buffered copy")
                .containsExactly("payload", "payload");
    }

    @Test
    void a_declared_visibility_links_every_pointer_in_declaration_order() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));
        List<String> order = new ArrayList<>();

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a")
                        .andThrough((hash, target) -> {
                            order.add("native:" + hash);
                            target.write("mirror/a", new ByteArrayInputStream(hash.getBytes(StandardCharsets.UTF_8)));
                        })
                        .andAt("/raw/alias"));

        assertThat(order).containsExactly("native:" + commit.hash());
        assertThat(publication.located("/raw/a")).isPresent();
        assertThat(publication.located("/raw/alias")).isPresent();
        assertThat(store.exists("mirror/a")).isTrue();
        assertThat(observer.published).hasSize(1);
    }

    @Test
    void a_declined_layout_links_nothing_and_notifies_nobody() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.declined());

        assertThat(commit.disposition()).as("the chain accepted - the layout is what declined")
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(commit.visible()).isFalse();
        assertThat(publication.located("/raw/a")).isEmpty();
        assertThat(observer.published).isEmpty();
        assertThat(store.exists("blobs/" + commit.hash())).as("the screened blob stays for garbage collection").isTrue();
    }

    @Test
    void an_opaque_layout_that_links_its_own_pointer_still_notifies_once() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), accepted -> {
                    new Publication(accepted.store()).link("/raw/a", accepted.hash());
                    return Publication.Visibility.laidOut();
                });

        assertThat(commit.visible()).isTrue();
        assertThat(publication.located("/raw/a")).contains("blobs/" + commit.hash());
        assertThat(observer.servableWhenNotified).containsExactly(true);
    }

    @Test
    void a_layout_may_refine_the_descriptor_the_observers_are_notified_with() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));

        Publication.Commit commit = publication.commit(ArtifactDescriptor.at("npm", "/envelope"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a")
                        .describing(new ArtifactDescriptor("npm", "left-pad", "1.3.0", "/raw/a",
                                "application/octet-stream", false, null, -1L)));

        assertThat(observer.published).hasSize(1);
        ArtifactDescriptor notified = observer.published.getFirst();
        assertThat(notified.coordinate()).as("the coordinate the format only knew after parsing the stored bytes")
                .isEqualTo("left-pad");
        assertThat(notified.version()).isEqualTo("1.3.0");
        assertThat(notified.hash()).as("the content identity is stamped on by the operation, not by the format")
                .isEqualTo(commit.hash());
        assertThat(notified.size()).isEqualTo("payload".length());
        assertThat(commit.artifact()).isEqualTo(notified);
    }

    @Test
    void a_sidecar_may_not_be_written_into_the_serving_pointer_namespace() throws IOException {
        Publication publication = new Publication(store, List.of());

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), accepted -> {
                    accepted.sidecar("publish/raw/a", new byte[] {1});
                    return Publication.Visibility.laidOut();
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publish/")
                .hasMessageContaining("declare it in the returned Visibility");
        assertThat(publication.located("/raw/a")).isEmpty();
    }

    @Test
    void a_non_accept_verdict_lays_nothing_out_and_notifies_nobody() throws IOException {
        for (PublishInterceptor.Disposition verdict : List.of(
                PublishInterceptor.Disposition.QUARANTINE, PublishInterceptor.Disposition.REJECT)) {
            Recorder observer = new Recorder("/raw/" + verdict);
            Publication publication = new Publication(store, List.of(new Counting(verdict)), List.of(observer));
            AtomicBoolean laid = new AtomicBoolean();

            Publication.Commit commit = publication.commit(descriptor("/raw/" + verdict), bytes("body-" + verdict),
                    Publication.Republish.overwrite(), _ -> {
                        laid.set(true);
                        return Publication.Visibility.at("/raw/" + verdict);
                    });

            assertThat(commit.disposition()).isEqualTo(verdict);
            assertThat(commit.visible()).isFalse();
            assertThat(commit.hash()).as("the blob is stored whatever the verdict").isNotNull();
            assertThat(laid).as("a non-accepted body never reaches the layout").isFalse();
            assertThat(publication.located("/raw/" + verdict)).isEmpty();
            assertThat(observer.published).isEmpty();
        }
    }

    @Test
    void an_after_commit_observer_failure_is_contained_and_the_publish_stands() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        observer.throwing = true;
        Publication publication = new Publication(store, List.of(), List.of(observer));

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a"));

        assertThat(commit.visible()).isTrue();
        assertThat(publication.located("/raw/a")).contains("blobs/" + commit.hash());
    }

    // --- the republish policy, supplied as data --------------------------------------------------------------------

    @Test
    void overwrite_moves_the_pointer_and_never_even_probes() throws IOException {
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        Publication publication = new Publication(counting, List.of());

        publication.commit(descriptor("/raw/a"), bytes("first"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a"));
        int afterFirst = counting.calls(FaultInjectingStore.Op.READ_VERSIONED);
        Publication.Commit second = publication.commit(descriptor("/raw/a"), bytes("second"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a"));

        assertThat(new Publication(store).blob("/raw/a")).contains(second.hash());
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED) - afterFirst)
                .as("OVERWRITE adds no probe: the only versioned read is the pointer link's own compare-and-set")
                .isEqualTo(1);
    }

    @Test
    void idempotent_converges_on_identical_bytes_and_refuses_different_ones() throws IOException {
        Publication publication = new Publication(store, List.of());
        Publication.AcceptedLayout layout = _ -> Publication.Visibility.at("/raw/a");

        Publication.Commit first = publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.idempotent(), layout);
        Publication.Commit replay = publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.idempotent(), layout);

        assertThat(replay.hash()).isEqualTo(first.hash());
        assertThat(replay.visible()).as("a byte-identical re-publish converges rather than conflicting").isTrue();

        assertThatThrownBy(() -> publication.commit(
                descriptor("/raw/a"), bytes("different"), Publication.Republish.idempotent(), layout))
                .isInstanceOf(Publication.RepublishConflict.class)
                .satisfies(thrown -> {
                    Publication.RepublishConflict conflict = (Publication.RepublishConflict) thrown;
                    assertThat(conflict.pointer()).isEqualTo("publish/raw/a");
                    assertThat(conflict.published()).isEqualTo(first.hash());
                    assertThat(conflict.offered()).isNotEqualTo(first.hash());
                });
        assertThat(new Publication(store).blob("/raw/a")).as("the refused republish never moved the pointer")
                .contains(first.hash());
    }

    @Test
    void refused_rejects_even_a_byte_identical_republish() throws IOException {
        Publication publication = new Publication(store, List.of());
        Publication.AcceptedLayout layout = _ -> Publication.Visibility.at("/raw/a");

        Publication.Commit first = publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.refused(), layout);

        assertThatThrownBy(() -> publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.refused(), layout))
                .isInstanceOf(Publication.RepublishConflict.class)
                .hasMessageContaining("refuses a re-publish of identical bytes");
        assertThat(new Publication(store).blob("/raw/a")).contains(first.hash());
    }

    @Test
    void a_policy_may_probe_a_format_owned_pointer_rather_than_the_publish_namespace() throws IOException {
        Publication publication = new Publication(store, List.of());

        Publication.Commit first = publication.commit(ArtifactDescriptor.at("npm", "/envelope"), bytes("payload"),
                Publication.Republish.refused("npm/left-pad/tarballs/left-pad-1.3.0.tgz"),
                _ -> Publication.Visibility.through((hash, target) ->
                        target.write("npm/left-pad/tarballs/left-pad-1.3.0.tgz",
                                new ByteArrayInputStream(hash.getBytes(StandardCharsets.UTF_8)))));

        assertThat(first.visible()).isTrue();
        assertThatThrownBy(() -> publication.commit(ArtifactDescriptor.at("npm", "/envelope"), bytes("other"),
                Publication.Republish.refused("npm/left-pad/tarballs/left-pad-1.3.0.tgz"),
                _ -> Publication.Visibility.laidOut()))
                .isInstanceOf(Publication.RepublishConflict.class)
                .hasMessageContaining("npm/left-pad/tarballs/left-pad-1.3.0.tgz");
    }

    @Test
    void the_republish_policy_is_gated_before_any_layout_write() throws IOException {
        Publication publication = new Publication(store, List.of());
        publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.refused(), _ -> Publication.Visibility.at("/raw/a"));
        AtomicBoolean laid = new AtomicBoolean();

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("other"),
                Publication.Republish.refused(), accepted -> {
                    laid.set(true);
                    accepted.sidecar("side/never", new byte[] {1});
                    return Publication.Visibility.at("/raw/a");
                }))
                .isInstanceOf(Publication.RepublishConflict.class);

        assertThat(laid).as("a refused republish fails loudly before the layout writes anything").isFalse();
        assertThat(store.exists("side/never")).isFalse();
    }

    @Test
    void an_overwrite_policy_may_not_name_a_pointer_it_never_probes() {
        assertThatThrownBy(() -> new Publication.Republish(Publication.Republish.Mode.OVERWRITE, "publish/raw/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probes no pointer");
    }

    // --- the crash matrix: one leg per step of the commit order ----------------------------------------------------

    /** A crash before the content-addressed write: nothing stored, nothing servable, nothing observed. */
    @Test
    void a_crash_storing_the_body_leaves_nothing() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store).failNext(FaultInjectingStore.Op.WRITE_BLOB);
        Publication publication = new Publication(faulty, List.of(), List.of(observer));

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a")))
                .isInstanceOf(IOException.class);

        assertThat(new Publication(store).located("/raw/a")).isEmpty();
        assertThat(observer.published).isEmpty();
        assertThat(store.list("blobs")).isEmpty();
    }

    /** A crash after the blob landed but before the caller learned it did: an unreferenced blob, nothing servable. */
    @Test
    void a_crash_after_the_body_landed_leaves_an_unreferenced_blob_and_no_visibility() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .crashAfterWrite(FaultInjectingStore.Op.WRITE_BLOB, FaultInjectingStore.anyKey());
        Publication publication = new Publication(faulty, List.of(), List.of(observer));

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a")))
                .isInstanceOf(IOException.class);

        assertThat(store.list("blobs")).as("the content-addressed write landed").hasSize(1);
        assertThat(new Publication(store).located("/raw/a")).as("but nothing points at it").isEmpty();
        assertThat(observer.published).isEmpty();
        new StoreInvariants(store).assertNoDanglingPointer();
    }

    /** A crash while the layout writes a sidecar: the sidecar may be there, the artifact is not servable. */
    @Test
    void a_crash_writing_a_sidecar_leaves_nothing_servable_and_converges_on_replay() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .crashAfterWrite(FaultInjectingStore.Op.WRITE, FaultInjectingStore.keyPrefix("side/"));
        Publication publication = new Publication(faulty, List.of(), List.of(observer));
        Publication.AcceptedLayout layout = accepted -> {
            accepted.sidecar("side/raw/a.meta", "derived".getBytes(StandardCharsets.UTF_8));
            return Publication.Visibility.at("/raw/a");
        };

        assertThatThrownBy(() -> publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout))
                .isInstanceOf(IOException.class);
        assertThat(store.exists("side/raw/a.meta")).as("the sidecar landed").isTrue();
        assertThat(new Publication(store).located("/raw/a")).as("but nothing serves it").isEmpty();
        assertThat(observer.published).isEmpty();

        faulty.heal();
        Publication.Commit replay = publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);

        assertThat(replay.visible()).isTrue();
        assertThat(new Publication(store).located("/raw/a")).contains("blobs/" + replay.hash());
        assertThat(observer.published).as("the replay completes the publish and notifies once").hasSize(1);
    }

    /** A crash linking the serving pointer - the commit point itself: nothing serves, nothing is observed. */
    @Test
    void a_crash_linking_the_pointer_leaves_nothing_servable() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
        Publication publication = new Publication(faulty, List.of(), List.of(observer));

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), accepted -> {
                    accepted.sidecar("side/raw/a.meta", "derived".getBytes(StandardCharsets.UTF_8));
                    return Publication.Visibility.at("/raw/a");
                }))
                .isInstanceOf(IOException.class);

        assertThat(new Publication(store).located("/raw/a")).isEmpty();
        assertThat(observer.published).as("no observer rides an uncommitted publish").isEmpty();
        new StoreInvariants(store).assertNoDanglingPointer();
    }

    /** A losing compare-and-set is a benign conflict the pointer write retries through, not a failure. */
    @Test
    void a_compare_and_set_conflict_is_retried_and_a_persistent_one_fails_by_name() throws IOException {
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .conflictNext(FaultInjectingStore.keyPrefix("publish/"));
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(faulty, List.of(), List.of(observer));

        Publication.Commit commit = publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/a"));
        assertThat(commit.visible()).isTrue();
        assertThat(observer.published).hasSize(1);

        for (int attempt = 0; attempt < Retries.COMPARE_AND_SET; attempt++) {
            faulty.conflictNext(FaultInjectingStore.keyPrefix("publish/raw/b"));
        }
        assertThatThrownBy(() -> publication.commit(descriptor("/raw/b"), bytes("other"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at("/raw/b")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("repeated version conflicts");
        assertThat(new Publication(store).located("/raw/b")).isEmpty();
        assertThat(observer.published).as("a pointer that never landed is never observed").hasSize(1);
    }

    /**
     * The documented crash window: the pointer landed but the caller never learned it did, so the after-commit
     * observers were not notified while the artifact <em>is</em> servable. This is exactly why the declared delivery
     * class is best-effort-repaired-by-the-walk rather than at-least-once - and a replay heals it.
     */
    @Test
    void a_crash_between_the_commit_point_and_the_notification_serves_without_observing() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
        Publication publication = new Publication(faulty, List.of(), List.of(observer));
        Publication.AcceptedLayout layout = _ -> Publication.Visibility.at("/raw/a");

        assertThatThrownBy(() -> publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout))
                .isInstanceOf(IOException.class);

        assertThat(new Publication(store).located("/raw/a")).as("the artifact serves").isPresent();
        assertThat(observer.published).as("but no observer saw it - the commit-to-callback window").isEmpty();

        faulty.heal();
        publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);
        assertThat(observer.published).as("a replay heals the missed notification").hasSize(1);
    }

    /** A multi-pointer layout is not atomic across its pointers, and the operation says so rather than reporting a
     *  publish that only half happened as complete. */
    @Test
    void a_crash_between_two_declared_pointers_leaves_partial_visibility_and_no_notification() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/raw/alias"));
        Publication publication = new Publication(faulty, List.of(), List.of(observer));
        Publication.AcceptedLayout layout = _ -> Publication.Visibility.at("/raw/a").andAt("/raw/alias");

        assertThatThrownBy(() -> publication.commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout))
                .isInstanceOf(IOException.class);

        assertThat(new Publication(store).located("/raw/a")).as("the first declared pointer landed").isPresent();
        assertThat(new Publication(store).located("/raw/alias")).as("the second did not").isEmpty();
        assertThat(observer.published).isEmpty();

        faulty.heal();
        publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);
        assertThat(new Publication(store).located("/raw/alias")).as("a replay completes the visibility").isPresent();
        assertThat(observer.published).hasSize(1);
    }

    /** A crash in a format-native visibility step is reported exactly like a failing {@code publish/} pointer. */
    @Test
    void a_crash_in_a_native_visibility_step_fails_the_commit() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .failEveryOn(FaultInjectingStore.Op.WRITE, FaultInjectingStore.keyPrefix("mirror/"));
        Publication publication = new Publication(faulty, List.of(), List.of(observer));

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.through((hash, target) ->
                        target.write("mirror/a", new ByteArrayInputStream(hash.getBytes(StandardCharsets.UTF_8))))))
                .isInstanceOf(IOException.class);

        assertThat(observer.published).isEmpty();
    }

    /** A crash reading the republish probe fails closed: the policy could not be evaluated, so nothing lays out. */
    @Test
    void a_crash_probing_the_republish_pointer_lays_nothing_out() throws IOException {
        FaultInjectingStore faulty = FaultInjectingStore.wrap(store)
                .failEveryOn(FaultInjectingStore.Op.READ_VERSIONED, FaultInjectingStore.keyPrefix("publish/raw/a"));
        Publication publication = new Publication(faulty, List.of());
        AtomicBoolean laid = new AtomicBoolean();

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.idempotent(), _ -> {
                    laid.set(true);
                    return Publication.Visibility.at("/raw/a");
                }))
                .isInstanceOf(IOException.class);

        assertThat(laid).isFalse();
        assertThat(new Publication(store).located("/raw/a")).isEmpty();
    }

    /** A layout that throws never reaches the commit point. */
    @Test
    void a_throwing_layout_leaves_nothing_servable() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));

        assertThatThrownBy(() -> publication.commit(descriptor("/raw/a"), bytes("payload"),
                Publication.Republish.overwrite(), _ -> {
                    throw new IOException("the format could not parse the accepted body");
                }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("could not parse");

        assertThat(publication.located("/raw/a")).isEmpty();
        assertThat(observer.published).isEmpty();
    }

    // --- byte-identical replay -------------------------------------------------------------------------------------

    @Test
    void a_byte_identical_republish_leaves_the_store_byte_identical() throws IOException {
        Recorder observer = new Recorder("/raw/a");
        Publication publication = new Publication(store, List.of(), List.of(observer));
        Publication.AcceptedLayout layout = accepted -> {
            accepted.sidecar("side/raw/a.meta", ("size=" + accepted.size()).getBytes(StandardCharsets.UTF_8));
            return Publication.Visibility.at("/raw/a").andAt("/raw/alias");
        };

        publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);
        Map<String, String> first = snapshot();
        publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);

        assertThat(snapshot()).as("a replay of identical bytes converges onto exactly the same stored objects")
                .isEqualTo(first);
        assertThat(observer.published).as("but the observers are notified again - delivery is at-least-once for them")
                .hasSize(2);
    }

    /**
     * The convergence claim in full: a crash injected at each of the three durable steps of the commit order (the
     * content-addressed write, a sidecar write, the pointer write) is reported, and the replay that follows lands the
     * store in exactly the state an uninterrupted publish produces - object for object, byte for byte.
     */
    @Test
    void a_replay_after_a_crash_at_every_step_converges_on_the_completed_publish() throws IOException {
        Publication.AcceptedLayout layout = accepted -> {
            accepted.sidecar("side/raw/a.meta", ("size=" + accepted.size()).getBytes(StandardCharsets.UTF_8));
            return Publication.Visibility.at("/raw/a");
        };
        Path reference = Files.createDirectory(root.resolve("reference"));
        ArtifactStore clean = at(reference);
        new Publication(clean, List.of()).commit(
                descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);
        Map<String, String> complete = objects(reference);
        assertThat(complete).as("the reference publish really wrote something").isNotEmpty();

        for (FaultInjectingStore.Op step : List.of(FaultInjectingStore.Op.WRITE_BLOB, FaultInjectingStore.Op.WRITE,
                FaultInjectingStore.Op.WRITE_VERSIONED)) {
            Path scope = Files.createDirectory(root.resolve("replay-" + step));
            ArtifactStore isolated = at(scope);
            FaultInjectingStore faulty = FaultInjectingStore.wrap(isolated)
                    .crashAfterWrite(step, FaultInjectingStore.anyKey());
            Publication publication = new Publication(faulty, List.of());

            assertThatThrownBy(() -> publication.commit(
                    descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout))
                    .as("a crash at " + step + " must be reported, never silently succeed")
                    .isInstanceOf(IOException.class);
            faulty.heal();
            publication.commit(descriptor("/raw/a"), bytes("payload"), Publication.Republish.overwrite(), layout);

            assertThat(objects(scope))
                    .as("a replay after a crash at " + step + " converges onto the completed publish")
                    .isEqualTo(complete);
            new StoreInvariants(isolated).assertConsistent();
        }
    }

    private static ArtifactStore at(Path scope) {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? scope.toString() : null);
    }

    /** Every stored object under {@code scope} as key &rarr; content digest. */
    private static Map<String, String> objects(Path scope) throws IOException {
        Map<String, String> objects = new TreeMap<>();
        try (Stream<Path> files = Files.walk(scope)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                objects.put(scope.relativize(file).toString().replace(File.separatorChar, '/'), digest(file));
            }
        }
        return objects;
    }

    private static String digest(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
