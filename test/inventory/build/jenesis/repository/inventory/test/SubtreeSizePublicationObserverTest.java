package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.inventory.SubtreeSizePublicationObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The subtree-size roll-up's live-event route ({@link SubtreeSizePublicationObserver}) maintains every browse folder's
 * cached {@code sizes/} total as a running counter on each publish and delete, so the full walk becomes the periodic
 * reconcile backstop rather than the only thing that keeps a folder's size current - the same running-counter /
 * full-scan pairing {@code QuotaArtifactStore}'s {@code adjust} / {@code recompute} tests pin for the quota meter. A
 * publish adds the blob's size up the ancestor chain and to the O(1) per-repo/tenant total (read through
 * {@code subtreeSize("")}) without ever listing the tree (no full walk on the hot path); a delete subtracts the same,
 * reading the blob's size before the separate garbage-collection pass reclaims it, and an already-collected blob is
 * safe (no throw, no double-subtract); and the unchanged {@code rollUpSizes()} full walk heals a dropped increment back
 * to the durable pointer truth.
 */
class SubtreeSizePublicationObserverTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private ListCountingStore counting;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        counting = new ListCountingStore(store);
        // Only the observer under test runs (no ServiceLoader discovery), fired through the real after-commit seam.
        publication = new Publication(counting, List.of(), List.of(new SubtreeSizePublicationObserver()));
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void a_publish_increments_the_ancestor_chain_and_the_total_without_a_full_walk() throws IOException {
        publish("/test/grp/lib/1.0/lib-1.0.bin", 100);
        publish("/test/grp/lib/2.0/lib-2.0.bin", 250);
        publish("/test/grp/tool/1.0/tool-1.0.bin", 70);

        assertThat(inventory().subtreeSize("/test/grp/lib/1.0")).as("a version folder holds its one blob").hasValue(100);
        assertThat(inventory().subtreeSize("/test/grp/lib")).as("a folder sums its versions incrementally").hasValue(350);
        assertThat(inventory().subtreeSize("/test/grp/tool")).hasValue(70);
        assertThat(inventory().subtreeSize("/test/grp")).hasValue(420);
        assertThat(inventory().subtreeSize("")).as("the O(1) per-repo/tenant running total").hasValue(420);

        // The hot path proves it never re-walks the tree: the after-commit fold lists nothing - the whole-tree walk
        // this observer retires would have listed every folder. Measured tightly around published() (the observed
        // seam), which folds through O(depth) point reads and compare-and-set increments only.
        String hash = publication.storeBlob(payload(30));
        publication.link("/test/grp/lib/3.0/lib-3.0.bin", hash);
        int before = counting.lists();
        publication.published(ArtifactDescriptor.at(null, "/test/grp/lib/3.0/lib-3.0.bin").withBlob(hash, 30));
        assertThat(counting.lists()).as("the publish fold never lists the tree - no full walk on the hot path").isEqualTo(before);
        assertThat(inventory().subtreeSize("/test/grp/lib")).hasValue(380);
        assertThat(inventory().subtreeSize("")).as("the total advanced by the new blob only").hasValue(450);
    }

    @Test
    void a_review_pointer_is_not_a_published_artifact_and_never_reaches_the_browse_totals() throws IOException {
        publish("/test/grp/lib/1.0/lib-1.0.bin", 100);
        assertThat(inventory().subtreeSize("")).hasValue(100);

        // A retroactive hold links publish/quarantine<path> WITHOUT unpublishing the artifact's own pointer, so for
        // the window of the hold the same bytes sit under two publish/ keys. Folding the review pointer counted them
        // twice in the repository's total - and in a `quarantine` folder the console deliberately hides, so the
        // inflation had no row an operator could look at to explain it.
        String hash = publication.storeBlob(payload(100));
        publication.link("/quarantine/test/grp/lib/1.0/lib-1.0.bin", hash);
        publication.published(ArtifactDescriptor.at(null, "/quarantine/test/grp/lib/1.0/lib-1.0.bin")
                .withBlob(hash, 100));

        assertThat(inventory().subtreeSize(""))
                .as("a held artifact is stored once and counted once: the review pointer is a review handle, not a "
                        + "second publication")
                .hasValue(100);
        assertThat(inventory().subtreeSize("/quarantine"))
                .as("and the reserved review subtree gets no cached row at all - browse hides the folder, so a size "
                        + "for it is a number nothing can display")
                .isEmpty();
    }

    @Test
    void a_delete_decrements_the_chain_and_total_and_an_already_collected_blob_is_safe() throws IOException {
        String hash = publish("/test/grp/lib/1.0/lib-1.0.bin", 100);
        publish("/test/grp/tool/1.0/tool-1.0.bin", 70);
        assertThat(inventory().subtreeSize("")).as("both blobs summed into the total").hasValue(170);

        // The real removal path: unpublish deletes the serving pointer, then fires onDeleted - the blob is still stored
        // (garbage collection is a separate later pass), so its size reads back and the decrement is exact.
        publication.unpublish("/test/grp/lib/1.0/lib-1.0.bin");
        assertThat(inventory().subtreeSize("/test/grp/lib/1.0")).as("the version folder emptied").hasValue(0);
        assertThat(inventory().subtreeSize("/test/grp/lib")).hasValue(0);
        assertThat(inventory().subtreeSize("")).as("the total dropped by exactly the deleted blob").hasValue(70);
        assertThat(inventory().subtreeSize("/test/grp/tool")).as("a sibling is untouched").hasValue(70);

        // Now the blob really is garbage-collected. A repeated removal reads no size, so it neither throws through the
        // (contained) observer call nor double-subtracts a folder a prior delete already un-counted.
        store.delete("blobs/" + hash);
        SubtreeSizePublicationObserver observer = new SubtreeSizePublicationObserver();
        ArtifactDescriptor ghost = ArtifactDescriptor.at(null, "/test/grp/lib/1.0/lib-1.0.bin").withBlob(hash, -1L);
        assertThatCode(() -> observer.onDeleted(ghost, store)).doesNotThrowAnyException();
        assertThat(inventory().subtreeSize("")).as("no double-subtract: the total holds").hasValue(70);
        assertThat(inventory().subtreeSize("/test/grp/tool")).hasValue(70);
    }

    @Test
    void the_full_walk_reconcile_heals_a_dropped_increment() throws IOException {
        publish("/test/grp/lib/1.0/lib-1.0.bin", 100);
        publish("/test/grp/lib/2.0/lib-2.0.bin", 250);

        // Simulate a dropped increment: link a blob WITHOUT firing the observer (a publish before this plugin was
        // discovered, or a compare-and-set given up under sustained contention), so the cached chain undercounts the
        // durable pointer tree - the exact drift QuotaArtifactStore's dropped-delta test leaves for its recompute.
        String hash = publication.storeBlob(payload(70));
        publication.link("/test/grp/tool/1.0/tool-1.0.bin", hash);
        assertThat(inventory().subtreeSize("")).as("the total drifted low - the tool publish was never folded").hasValue(350);

        // The reconcile backstop - the UNCHANGED full walk, run on the retention-sweep cadence - recomputes from the
        // pointer tree and heals the drift: the cached sizes and the total now match the full-walk truth.
        long total = inventory().rollUpSizes();
        assertThat(total).as("the walk returns the whole-repository truth").isEqualTo(420);
        assertThat(inventory().subtreeSize("")).as("the healed per-repo total").hasValue(420);
        assertThat(inventory().subtreeSize("/test/grp/tool")).as("the missed folder is now correct").hasValue(70);
        assertThat(inventory().subtreeSize("/test/grp/lib")).hasValue(350);
    }

    @Test
    void an_over_large_delete_floors_every_cached_size_and_the_total_at_zero() throws IOException {
        publish("/test/grp/lib/1.0/lib-1.0.bin", 100);
        assertThat(inventory().subtreeSize("")).as("one 100-byte blob summed into the total").hasValue(100);

        // An over-large decrement: onDeleted carries a RECORDED size (250) larger than the 100 the folders actually hold
        // - a republish-repoint drift, a double-delete, or a size mismatch. Without a floor the counters would go
        // negative; the Math.max(0L, current + delta) floor (SubtreeSizePublicationObserver ~line 147) keeps every folder
        // and the per-repo/tenant total at >= 0.
        SubtreeSizePublicationObserver observer = new SubtreeSizePublicationObserver();
        ArtifactDescriptor over = ArtifactDescriptor.at(null, "/test/grp/lib/1.0/lib-1.0.bin").withBlob("deadbeef", 250);
        observer.onDeleted(over, store);

        assertThat(inventory().subtreeSize("/test/grp/lib/1.0")).as("the version folder floors at zero, never negative").hasValue(0);
        assertThat(inventory().subtreeSize("/test/grp/lib")).as("the parent folder floors at zero").hasValue(0);
        assertThat(inventory().subtreeSize("/test/grp")).as("the grandparent folder floors at zero").hasValue(0);
        assertThat(inventory().subtreeSize("")).as("the per-repo/tenant total floors at zero, never negative").hasValue(0);
    }

    /** Store a blob, link its serving pointer, and fire the after-commit publish observer with the blob's identity -
     *  the ingress edge's storeBlob + link + published choreography, so the roll-up observer folds the publish. */
    private String publish(String path, int size) throws IOException {
        String hash = publication.storeBlob(payload(size));
        publication.link(path, hash);
        publication.published(ArtifactDescriptor.at(null, path).withBlob(hash, size));
        return hash;
    }

    /** A blob body of a known size - {@code size} bytes of {@code 'x'} - so a folder's rolled-up total is a plain sum. */
    private static ByteArrayInputStream payload(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'x');
        return new ByteArrayInputStream(bytes);
    }

    /** Forwards to a real store but tallies every {@link ArtifactStore#list} call, so a test can prove the publish hot
     *  path folds sizes through O(depth) point reads and compare-and-set writes without ever listing (walking) the
     *  tree - the whole-tree walk this observer exists to retire off the hot path. */
    private record ListCountingStore(ArtifactStore delegate, int[] count) implements ArtifactStore {

        ListCountingStore(ArtifactStore delegate) {
            this(delegate, new int[1]);
        }

        int lists() {
            return count[0];
        }

        /** The delegate's: a decorator is the same store, so what the node keeps per store identity - here the
         *  observer's deferred deltas - is what the inventory reading the delegate sees. */
        @Override
        public Object identity() {
            return delegate.identity();
        }

        @Override
        public List<String> list(String prefix) {
            count[0]++;
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
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
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /**
     * A re-publish at the same path folds the difference, not the whole size again.
     *
     * <p>The observer used to add {@code +size} to every ancestor per delivery without asking what that path
     * already contributed, so publishing the same coordinate twice counted it twice and quota and browse totals
     * read high until the next {@code rollUpSizes()} swept the drift away. The class's best-effort wording covers
     * a <em>dropped</em> delta, never a doubled one.
     *
     * <p>Driven through {@code commit}, because that is the choreography that knows: it reads the pointer it is
     * about to overwrite and describes the blob it replaced. The hand-rolled {@code link}-then-{@code published}
     * helper above cannot, and deliberately still folds the whole size - an absent value is no information, not a
     * first publish.
     */
    @Test
    void a_re_publish_at_the_same_path_folds_the_difference_rather_than_the_whole_size() throws IOException {
        String path = "/test/grp/lib/1.0/lib-1.0.bin";

        commit(path, 100);
        assertThat(inventory().subtreeSize("")).as("the first publish is the whole size").hasValue(100);

        commit(path, 100);
        assertThat(inventory().subtreeSize(""))
                .as("a byte-identical re-publish replaces an equal contribution, so the total does not move")
                .hasValue(100);
        assertThat(inventory().subtreeSize("/test/grp/lib"))
                .as("and no ancestor moves either").hasValue(100);

        commit(path, 250);
        assertThat(inventory().subtreeSize(""))
                .as("a re-publish with different bytes folds the difference - 250 in place of 100, not on top of it")
                .hasValue(250);
        assertThat(inventory().subtreeSize("/test/grp/lib/1.0")).hasValue(250);
    }

    /** One hosted publish through the choke point that knows what it overwrote. */
    private void commit(String path, int size) throws IOException {
        publication.commit(ArtifactDescriptor.at(null, path), payload(size),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at(path));
    }
}
