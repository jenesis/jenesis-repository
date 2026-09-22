package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.TornWriteReconciler;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.WalkSegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-ordering reconcile sweep - the minimal-harm dual of the mark-sweep garbage collector. It asserts that
 * every crash-torn intermediate the two-step publish (blob first, then pointer) can leave converges to either fully
 * published or fully absent, never partial: a pointer whose blob is missing is flagged and (on apply) removed; a blob
 * no pointer references is confirmed but left to the collector, never double-handled; a re-run is a no-op; and a
 * referenced object is never removed. The ordering guarantee itself is pinned by injecting a crash between the blob
 * and pointer writes and proving the residue is a benign orphan, never a dangling pointer. Driven end to end through
 * the store SPI over a real filesystem store; no framework.
 */
class TornWriteReconcileTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    /** A syntactically valid SHA-256 that no blob is ever stored under - the target of an injected dangling pointer. */
    private static final String MISSING = "a".repeat(64);
    /** The shared store reference walk, the same primitive the scheduler resolves - Pass 1 streams the pointer tree
     *  through it instead of buffering the whole leaf set. */
    private static final ArtifactWalk WALK = WalkProvider.resolve(key -> null).orElseThrow();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private TornWriteReconciler reconciler() {
        return new TornWriteReconciler(store, WALK);
    }

    @Test
    void a_fully_published_artifact_reconciles_nothing_and_the_pass_is_idempotent() throws IOException {
        publish("kept", "1.0.0");
        new StoreInvariants(store).assertConsistent();

        TornWriteReconciler.Result first = reconciler().reconcile(true, NOW);
        assertThat(first).as("a converged store has nothing torn")
                .isEqualTo(new TornWriteReconciler.Result(0, 0, 0));

        TornWriteReconciler.Result second = reconciler().reconcile(true, NOW);
        assertThat(second).as("idempotent: a second pass is a no-op")
                .isEqualTo(new TornWriteReconciler.Result(0, 0, 0));
        new StoreInvariants(store).assertConsistent();
    }

    @Test
    void a_dangling_pointer_is_flagged_dry_run_then_removed_leaving_the_store_fully_absent() throws IOException {
        // The harmful state the ordering forbids: a pointer resolving to a blob that was never stored.
        String pointer = "publish" + path("dangling", "1.0.0");
        store.writeVersioned(pointer, MISSING.getBytes(StandardCharsets.UTF_8), null);
        assertThatThrownBy(() -> new StoreInvariants(store).assertNoDanglingPointer())
                .as("the store-invariant checker sees the dangling pointer").isInstanceOf(AssertionError.class);

        // Dry run: flag and count, mutate nothing.
        TornWriteReconciler.Result dryRun = reconciler().reconcile(false, NOW);
        assertThat(dryRun).as("dry run flags the one dangling pointer but removes nothing")
                .isEqualTo(new TornWriteReconciler.Result(1, 0, 0));
        assertThat(store.readVersioned(pointer)).as("the dry run left the pointer in place").isPresent();

        // Apply: remove the dangling pointer.
        TornWriteReconciler.Result applied = reconciler().reconcile(true, NOW);
        assertThat(applied).as("apply removes the dangling pointer")
                .isEqualTo(new TornWriteReconciler.Result(1, 1, 0));
        assertThat(store.readVersioned(pointer)).as("the dangling pointer is gone - fully absent").isEmpty();
        new StoreInvariants(store).assertNoDanglingPointer();

        // Idempotent: a re-run over the converged store finds nothing.
        assertThat(reconciler().reconcile(true, NOW)).as("idempotent re-run is a no-op")
                .isEqualTo(new TornWriteReconciler.Result(0, 0, 0));
    }

    @Test
    void an_orphan_blob_is_confirmed_but_never_removed_it_is_the_collectors_domain() throws IOException {
        // A blob with no pointer - exactly what a crash between the blob and pointer writes leaves.
        String hash = new Publication(store).storeBlob(
                new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists("blobs/" + hash)).isTrue();

        TornWriteReconciler.Result result = reconciler().reconcile(true, NOW);
        assertThat(result).as("the orphan is confirmed, nothing is dangling, nothing is removed")
                .isEqualTo(new TornWriteReconciler.Result(0, 0, 1));
        assertThat(store.exists("blobs/" + hash))
                .as("the orphan blob is left for the garbage collector, never removed here").isTrue();

        // Re-run confirms the same orphan again without ever removing it (no double-handling).
        assertThat(reconciler().reconcile(true, NOW)).isEqualTo(new TornWriteReconciler.Result(0, 0, 1));
        assertThat(store.exists("blobs/" + hash)).isTrue();
    }

    @Test
    void a_crash_between_the_blob_and_pointer_writes_leaves_an_orphan_not_a_dangling_pointer() throws IOException {
        // Publish through a store that crashes on the pointer write, after the blob write has landed: the exact torn
        // moment the ordering is designed around. storeBlob succeeds (blob durable), link throws before the pointer.
        FaultInjectingStore faulting = FaultInjectingStore.wrap(store);
        faulting.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish"));
        Publication publication = new Publication(faulting);
        String hash = publication.storeBlob(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> publication.link(path("crashed", "1.0.0"), hash))
                .as("the pointer write crashes").isInstanceOf(IOException.class);

        // The residue is a benign orphan blob, never a dangling pointer - the whole point of blob-before-pointer.
        assertThat(store.exists("blobs/" + hash)).as("the blob is durable").isTrue();
        new StoreInvariants(store).assertNoDanglingPointer();

        TornWriteReconciler.Result result = reconciler().reconcile(true, NOW);
        assertThat(result).as("the crash left an orphan (collector's domain), never a dangling pointer")
                .isEqualTo(new TornWriteReconciler.Result(0, 0, 1));
    }

    @Test
    void a_referenced_object_is_never_removed_only_the_dangling_pointer_is() throws IOException {
        // A fully published artifact alongside an injected dangling pointer: the sweep must remove only the dangling
        // one and never touch the referenced blob or its pointer.
        publish("kept", "1.0.0");
        String keptPointer = "publish" + path("kept", "1.0.0");
        String keptHash = ServableNames.hash(store.readVersioned(keptPointer).orElseThrow().content());
        String danglingPointer = "publish" + path("dangling", "1.0.0");
        store.writeVersioned(danglingPointer, MISSING.getBytes(StandardCharsets.UTF_8), null);

        TornWriteReconciler.Result result = reconciler().reconcile(true, NOW);
        assertThat(result).as("only the dangling pointer is removed; the referenced object stands")
                .isEqualTo(new TornWriteReconciler.Result(1, 1, 0));

        assertThat(store.readVersioned(keptPointer)).as("the referenced pointer survives").isPresent();
        assertThat(store.exists("blobs/" + keptHash)).as("the referenced blob survives").isTrue();
        assertThat(store.readVersioned(danglingPointer)).as("the dangling pointer is gone").isEmpty();
        new StoreInvariants(store).assertConsistent();
    }

    @Test
    void pass_one_streams_the_pointer_tree_through_the_shared_walk_over_many_leaves() throws IOException {
        // A multi-leaf publish/ fixture - several fully-published artifacts and one injected dangling pointer. Pass 1
        // must stream every leaf through the shared walk (the resumable, bounded-stride primitive), never buffering the
        // whole leaf set into one list as it used to, and still detect and repair the torn write exactly.
        for (int i = 0; i < 5; i++) {
            publish("lib" + i, "1.0.0");
        }
        String dangling = "publish" + path("torn", "1.0.0");
        store.writeVersioned(dangling, MISSING.getBytes(StandardCharsets.UTF_8), null);

        SpyWalk spy = new SpyWalk(WALK);
        TornWriteReconciler.Result result = new TornWriteReconciler(store, spy).reconcile(true, NOW);

        // Detection/repair preserved: the one dangling pointer flagged and removed, the five referenced blobs untouched.
        assertThat(result).as("the torn write is detected and repaired, the referenced objects stand")
                .isEqualTo(new TornWriteReconciler.Result(1, 1, 0));
        assertThat(store.readVersioned(dangling)).as("the dangling pointer is removed").isEmpty();

        // Pass 1 rode the shared walk over the publish/ root, streaming every one of the six leaves through the visitor
        // (five published + the torn one) - proving it drove the streaming primitive, not a buffered list recursion.
        assertThat(spy.consumer).as("Pass 1 joins the torn-write walk consumer").isEqualTo("reconcile-torn");
        assertThat(spy.roots).as("over the publish/ root, the same root the sibling reconciler walks")
                .containsExactly("publish");
        assertThat(spy.streamed).as("every publish/ leaf was streamed through the walk visitor")
                .contains(dangling).hasSize(6);
        new StoreInvariants(store).assertConsistent();
    }

    /** An {@link ArtifactWalk} that delegates to the real store walk but records the consumer, roots and every key it
     *  streams to the visitor - proof that Pass 1 drives the shared streaming primitive over {@code publish/} rather
     *  than buffering the whole pointer set. */
    private static final class SpyWalk implements ArtifactWalk {

        private final ArtifactWalk delegate;
        final List<String> streamed = new ArrayList<>();
        String consumer;
        List<String> roots;

        SpyWalk(ArtifactWalk delegate) {
            this.delegate = delegate;
        }

        @Override
        public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
                throws IOException {
            this.consumer = consumer;
            this.roots = roots;
            return delegate.walk(store, consumer, roots, key -> {
                streamed.add(key);
                visitor.visit(key);
            });
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

    /** Publish an artifact the way an ingress edge does: store the blob content-addressed, then link its pointer -
     *  blob durable first, pointer second. */
    private void publish(String coordinate, String version) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(
                (coordinate + "@" + version).getBytes(StandardCharsets.UTF_8)));
        publication.link(path(coordinate, version), hash);
    }

    private static String path(String coordinate, String version) {
        return InventoryTestFormat.path(coordinate, version);
    }
}
