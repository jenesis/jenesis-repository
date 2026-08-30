package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ordered-paging contract of {@link ArtifactStore#page}, on the filesystem backend's bounded native override and
 * on the interface default (sort-and-filter over {@code list}) - both must answer identically: lexicographic order,
 * strictly after the boundary, capped at the limit, child containers and leaves alike, and empty for a missing
 * prefix or a non-positive limit.
 */
class PageTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** The filesystem store behind the interface's default {@code page}, so the fallback is what runs. */
    private static ArtifactStore fallback(ArtifactStore delegate) {
        return new ArtifactStore() {
            @Override
            public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer)
                    throws IOException {
                return delegate.scan(prefix, startAfter, limit, consumer);
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
            public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
                return delegate.writeVersioned(key, content, expected);
            }
        };
    }

    private static List<String> page(ArtifactStore store, String prefix, String startAfter, int limit) {
        List<String> names = new ArrayList<>();
        store.page(prefix, startAfter, limit, names::add);
        return names;
    }

    @Test
    void pages_in_order_strictly_after_the_boundary_and_bounded_by_the_limit() throws IOException {
        ArtifactStore store = store();
        for (String name : List.of("c", "a", "e", "b", "d")) {
            store.writeVersioned("dir/" + name, name.getBytes(StandardCharsets.UTF_8), null);
        }
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            assertThat(page(paged, "dir", "", 2)).containsExactly("a", "b");
            assertThat(page(paged, "dir", "b", 10)).containsExactly("c", "d", "e");
            assertThat(page(paged, "dir", "b", 1)).containsExactly("c");
            assertThat(page(paged, "dir", "e", 10)).isEmpty();
            assertThat(page(paged, "dir", "", 0)).isEmpty();
            assertThat(page(paged, "missing", "", 10)).isEmpty();
        }
    }

    @Test
    void a_child_that_prefixes_a_longer_sibling_name_pages_in_name_order() throws IOException {
        // Child-NAME order puts "banana" (a container) before "banana.txt" (a leaf), although the container's
        // raw keys (banana/...) sort past the leaf in plain key order ('.' < '/') - the contract every backend
        // must repair its native stream to, or a paging resume would silently drop the shorter child.
        ArtifactStore store = store();
        store.writeVersioned("dir/apple", new byte[0], null);
        store.writeVersioned("dir/banana/nested", new byte[0], null);
        store.writeVersioned("dir/banana.txt", new byte[0], null);
        store.writeVersioned("dir/cherry", new byte[0], null);
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            assertThat(page(paged, "dir", "", 10)).containsExactly("apple", "banana", "banana.txt", "cherry");
            assertThat(page(paged, "dir", "", 2)).containsExactly("apple", "banana");
            assertThat(page(paged, "dir", "banana", 10)).containsExactly("banana.txt", "cherry");
        }
    }

    @Test
    void page_hides_an_atomic_writes_in_flight_upload_temp_file() throws IOException {
        // page()'s native filesystem override filters a live .upload*.tmp exactly as list() does - a distinct code
        // path from the list() filter - so a concurrent atomic write's spool file (a sibling in the directory until it
        // is renamed into place) is never paged out as if it were a stored child.
        ArtifactStore store = store();
        store.writeVersioned("dir/one", "1".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("dir/two", "2".getBytes(StandardCharsets.UTF_8), null);
        Files.createFile(root.resolve("dir").resolve(".upload98765.tmp"));

        List<String> names = new ArrayList<>();
        store.page("dir", "", 10, names::add);
        assertThat(names).as("the in-flight upload temp file is filtered, only the real entries page")
                .containsExactly("one", "two");

        // The temp file sorts before "one", so a cap-then-emit that failed to filter would surface it as the first
        // (and only) name of a one-element page; assert it still does not.
        List<String> first = new ArrayList<>();
        store.page("dir", "", 1, first::add);
        assertThat(first).containsExactly("one");
    }

    /** A store whose {@code list} answers {@code size} synthetic children and inherits every other member, including
     *  the interface's {@code page} fallback - the shape of a backend that never overrode it. */
    private static ArtifactStore listingOnly(int size) {
        return new ArtifactStore() {
            @Override
            public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
                // Named explicitly, which is the point of the change that made scan abstract: the listing walk is
                // still available to a store whose key space is already materialised, but nothing inherits it by
                // accident any more.
                return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
            }

            @Override
            public List<String> list(String prefix) {
                List<String> names = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    names.add("child-" + index);
                }
                return names;
            }

            @Override
            public ArtifactStore scope(String tenant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean exists(String key) {
                return false;
            }

            @Override
            public void read(String key, OutputStream out) throws IOException {
                throw new IOException(key);
            }

            @Override
            public InputStream open(String key) throws IOException {
                throw new IOException(key);
            }

            @Override
            public void write(String key, InputStream in) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String writeBlob(InputStream in) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long size(String key) {
                return -1;
            }

            @Override
            public void delete(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Versioned> readVersioned(String key) {
                return Optional.empty();
            }

            @Override
            public boolean writeVersioned(String key, byte[] content, Object expected) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void the_inherited_page_fallback_refuses_a_child_set_it_would_have_to_materialise() {
        // The fallback sorts a whole list() to answer one page, so an implementation that inherits it turns a bounded
        // paging request into an unbounded heap allocation - the trap every shipped backend happened to avoid by
        // overriding. Past the bound it fails by name rather than materialising, and the refusal names the inheriting
        // class, the prefix and the remedy so the fix is not a guess.
        ArtifactStore inheriting = listingOnly(20_000);

        assertThatThrownBy(() -> page(inheriting, "blobs", "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blobs")
                .hasMessageContaining("20000")
                .hasMessageContaining("Override page");

        // Below the bound the fallback still answers, and answers correctly: this is a refusal at a stated ceiling,
        // not the removal of the fallback.
        assertThat(page(listingOnly(12), "blobs", "", 3))
                .containsExactly("child-0", "child-1", "child-10");
    }

    @Test
    void the_listing_fallback_is_available_by_name_for_a_store_that_holds_its_children_already() {
        // Deleting the fallback outright would force ~110 in-repo implementations - decorators and map-backed doubles
        // whose whole child set is genuinely in memory - to re-write this exact loop. It stays, but as a named call: a
        // store that means to page by listing says so, and the cost is a decision at the call site rather than an
        // accident of inheritance.
        ArtifactStore inheriting = listingOnly(4);
        List<String> names = new ArrayList<>();
        ArtifactStore.pageByListing(inheriting, "blobs", "child-1", 2, names::add);
        assertThat(names).containsExactly("child-2", "child-3");

        assertThatThrownBy(() -> ArtifactStore.pageByListing(listingOnly(ArtifactStore.MAX_INHERITED_CHILDREN + 1),
                "blobs", "", 1, _ -> { }))
                .as("the named form carries the same bound - opting in deliberately is not opting out of the ceiling")
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ArtifactStore.pageByListing(listingOnly(ArtifactStore.MAX_INHERITED_CHILDREN),
                "blobs", "", 1, _ -> { }))
                .as("exactly at the bound is still answered - the refusal is past it")
                .doesNotThrowAnyException();
    }

    @Test
    void containers_and_leaves_page_alike_and_a_full_traversal_matches_list() throws IOException {
        ArtifactStore store = store();
        store.writeVersioned("dir/leaf", new byte[0], null);
        store.writeVersioned("dir/nested/child", new byte[0], null);
        store.writeVersioned("dir/other/child", new byte[0], null);
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            List<String> names = new ArrayList<>();
            String startAfter = "";
            while (true) {
                List<String> batch = page(paged, "dir", startAfter, 1);
                if (batch.isEmpty()) {
                    break;
                }
                names.addAll(batch);
                startAfter = batch.getLast();
            }
            assertThat(names).containsExactlyElementsOf(store.list("dir"));
        }
    }
}
