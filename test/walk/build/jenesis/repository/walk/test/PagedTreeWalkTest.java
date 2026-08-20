package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bounded subtree walk over <em>pathological</em> trees - the shapes an attacker plants and a real repository
 * never has, which is exactly why every hand-rolled copy of this traversal eventually got one of them wrong. It pins
 * the four properties the primitive exists to guarantee: the descent is iterative, so a 20 000-segment key walks
 * instead of overflowing a thread stack; every cap is <em>visible</em>, so reaching one is never mistaken for a
 * complete listing (design gate 4); the continuation cursor resumes exactly at the boundary, including a crash-resume
 * from a cursor persisted through a real store, without skipping or duplicating a committed page; and a name a store
 * backend should never have returned is refused by name rather than walked.
 */
class PagedTreeWalkTest {

    @TempDir
    Path root;

    // ---- iterative depth: the property that makes a hand-rolled recursive descent a defect ----

    @Test
    void a_pathologically_deep_key_walks_without_overflowing_the_stack() throws IOException {
        MemoryStore store = new MemoryStore();
        StringBuilder key = new StringBuilder("root");
        for (int level = 0; level < 20_000; level++) {
            key.append("/a");
        }
        key.append("/leaf");
        String deep = key.toString();
        store.seed(deep);

        List<String> visited = new ArrayList<>();
        PagedTreeWalk walk = PagedTreeWalk.bounded().depth(20_002).steps(50_000);
        AtomicReference<Traversal.Result> result = new AtomicReference<>();
        assertThatCode(() -> result.set(walk.walk(store, "root", visited::add)))
                .as("a 20000-segment-deep key must not overflow the stack (iterative descent, not recursion)")
                .doesNotThrowAnyException();
        assertThat(visited).containsExactly(deep);
        assertThat(result.get().exhausted()).isTrue();
        assertThat(result.get().cursor()).isEmpty();
    }

    @Test
    void a_subtree_deeper_than_the_depth_cap_fails_by_name_rather_than_being_pruned() throws IOException {
        MemoryStore store = new MemoryStore();
        store.seed("root/a/b/c/d/e/f/leaf");
        store.seed("root/shallow");

        assertThatThrownBy(() -> PagedTreeWalk.bounded().depth(3).walk(store, "root", _ -> {
        }))
                .isInstanceOfSatisfying(TraversalException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(TraversalException.Reason.DEPTH);
                    assertThat(failure.key()).isEqualTo("root/a/b/c/d");
                })
                .as("a depth breach cannot be a truncation: no path-ordered cursor can resume beneath a refused subtree")
                .hasMessageContaining("depth ceiling of 3");
    }

    // ---- very wide: the flat namespace that must be paged, never materialised ----

    @Test
    void a_container_far_wider_than_one_page_is_paged_to_exhaustion_in_path_order() throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            String key = String.format("root/wide/pkg-%05d", index);
            store.seed(key);
            expected.add(key);
        }

        List<String> visited = new ArrayList<>();
        Traversal.Result result = PagedTreeWalk.bounded().page(64).entries(10_000).walk(store, "root", visited::add);

        assertThat(result.exhausted()).isTrue();
        assertThat(result.delivered()).isEqualTo(5_000);
        assertThat(visited).containsExactlyElementsOf(expected);
    }

    // ---- the caps are visible: truncation always carries a continuation ----

    @Test
    void the_entry_cap_hit_exactly_at_the_end_truncates_and_the_continuation_proves_exhaustion()
            throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> expected = new ArrayList<>();
        for (char letter = 'a'; letter <= 'e'; letter++) {
            String key = "root/" + letter;
            store.seed(key);
            expected.add(key);
        }

        List<String> first = new ArrayList<>();
        PagedTreeWalk walk = PagedTreeWalk.bounded().entries(5);
        Traversal.Result truncated = walk.walk(store, "root", first::add);

        assertThat(first).containsExactlyElementsOf(expected);
        assertThat(truncated.truncated())
                .as("a cap met exactly at the end may not claim completeness it did not prove")
                .isTrue();
        assertThat(truncated.cursor()).contains("root/e");

        List<String> second = new ArrayList<>();
        Traversal.Result exhausted = walk.walk(store, "root", truncated.cursor().orElseThrow(), second::add);
        assertThat(second).as("the continuation neither repeats nor invents a key").isEmpty();
        assertThat(exhausted.exhausted()).isTrue();
        assertThat(exhausted.cursor()).isEmpty();
    }

    @Test
    void the_entry_cap_hit_mid_page_resumes_at_the_boundary_without_skipping_or_duplicating() throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 37; index++) {
            String key = String.format("root/g%d/a-%02d", index % 3, index);
            store.seed(key);
            expected.add(key);
        }
        Collections.sort(expected);

        // A page far wider than the entry cap, so every truncation lands in the middle of a buffered page - the
        // boundary a hand-rolled "page then break" loop typically gets wrong by one.
        PagedTreeWalk walk = PagedTreeWalk.bounded().entries(4).page(1_000);
        List<String> everything = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        String cursor = null;
        Traversal.Result result;
        do {
            List<String> page = new ArrayList<>();
            result = walk.walk(store, "root", cursor, page::add);
            pages.add(page.size());
            everything.addAll(page);
            cursor = result.cursor().orElse(null);
        } while (result.truncated());

        assertThat(everything).as("paging the walk sees exactly the whole subtree, once each, in path order")
                .containsExactlyElementsOf(expected);
        assertThat(everything).doesNotHaveDuplicates();
        assertThat(pages).as("every page but the last is full").allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(4));
        assertThat(result.exhausted()).isTrue();
    }

    @Test
    void the_step_budget_fails_by_name_on_a_tree_of_empty_containers_that_delivers_no_entry() throws IOException {
        MemoryStore store = new MemoryStore();
        // A wide fan of containers whose only leaves sit far to the right: an entry cap alone would never fire here,
        // which is precisely why the step budget - charged per node opened - is a separate bound.
        for (int index = 0; index < 500; index++) {
            store.seed(String.format("root/c%04d/deep/leaf", index));
        }

        List<String> visited = new ArrayList<>();
        assertThatThrownBy(() -> PagedTreeWalk.bounded().steps(50).walk(store, "root", visited::add))
                .isInstanceOfSatisfying(TraversalException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(TraversalException.Reason.STEPS))
                .hasMessageContaining("more than 50 nodes");
        assertThat(visited).as("the budget bounds store work, so it fires long before the subtree is delivered")
                .hasSizeLessThan(500);
    }

    @Test
    void an_absent_subtree_is_exhausted_rather_than_an_error() throws IOException {
        Traversal.Result result = PagedTreeWalk.bounded().walk(new MemoryStore(), "root", _ -> {
            throw new AssertionError("nothing may be delivered");
        });

        assertThat(result.exhausted()).isTrue();
        assertThat(result.delivered()).isZero();
        assertThat(result.cursor()).isEmpty();
    }

    // ---- traversal-probe segments: a name no write path could have created is refused, not walked ----

    @Test
    void a_traversal_probe_segment_in_the_key_space_is_refused_by_name() {
        for (String planted : List.of("root/../etc/passwd", "root/./x/leaf", "root/a\\b/leaf")) {
            MemoryStore store = new MemoryStore();
            store.seed("root/ok/leaf");
            store.seed(planted);

            assertThatThrownBy(() -> PagedTreeWalk.bounded().walk(store, "root", _ -> {
            }))
                    .as("a store handing back the non-traversal-free name in '%s' is refused, never composed into a "
                            + "key", planted)
                    .isInstanceOfSatisfying(TraversalException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(TraversalException.Reason.SEGMENT));
        }
    }

    @Test
    void a_traversal_probe_root_is_refused_before_any_store_call() {
        for (String hostile : List.of("", "root/..", "../root", "root//sub", "root/a\\b", "root/")) {
            assertThatThrownBy(() -> PagedTreeWalk.bounded().walk(new MemoryStore(), hostile, _ -> {
            }))
                    .as("root '%s'", hostile)
                    .isInstanceOfSatisfying(TraversalException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(TraversalException.Reason.SEGMENT));
        }
    }

    @Test
    void a_cursor_aimed_outside_the_root_is_rejected_rather_than_silently_restarting() {
        assertThatThrownBy(() -> PagedTreeWalk.bounded().walk(new MemoryStore(), "root", "other/key", _ -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a key under the traversal root");
    }

    // ---- crash-resume from a cursor persisted through a real store ----

    @Test
    void a_crash_at_every_page_boundary_resumes_from_the_persisted_cursor_without_loss_or_duplication()
            throws IOException {
        List<String> keys = new ArrayList<>();
        for (char group = 'a'; group <= 'd'; group++) {
            for (int index = 0; index < 6; index++) {
                keys.add("publish/" + group + "/artifact-" + index);
            }
        }
        Collections.sort(keys);

        PagedTreeWalk walk = PagedTreeWalk.bounded().entries(5);
        for (int kill = 1; kill <= keys.size(); kill++) {
            ArtifactStore store = store("kill-" + kill);
            for (String key : keys) {
                store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
            }

            // Drive the walk page by page, committing each page's effects and only then its cursor - the discipline
            // the contract states - and kill the process after the n-th delivered key.
            List<String> committed = new ArrayList<>();
            int fatal = kill;
            try {
                String cursor = readCursor(store);
                Traversal.Result result;
                do {
                    List<String> page = new ArrayList<>();
                    result = walk.walk(store, "publish", cursor, key -> {
                        page.add(key);
                        if (committed.size() + page.size() == fatal) {
                            throw new IOException("crash after " + fatal);
                        }
                    });
                    committed.addAll(page);            // the page's effects commit first ...
                    cursor = result.cursor().orElse(null);
                    writeCursor(store, cursor);        // ... and the cursor only after them
                } while (result.truncated());
            } catch (IOException crash) {
                assertThat(crash).hasMessageContaining("crash after " + fatal);
            }

            // Restart from nothing but the store: a fresh walk, a cursor re-read from where it was durably left.
            List<String> resumed = new ArrayList<>();
            String cursor = readCursor(store);
            Traversal.Result result;
            do {
                result = walk.walk(store, "publish", cursor, resumed::add);
                cursor = result.cursor().orElse(null);
                writeCursor(store, cursor);
            } while (result.truncated());

            List<String> seen = new ArrayList<>(committed);
            seen.addAll(resumed);
            assertThat(seen).as("kill after %d: a committed page is never re-delivered", fatal).doesNotHaveDuplicates();
            assertThat(seen).as("kill after %d: nothing between two committed pages is skipped", fatal)
                    .containsExactlyElementsOf(keys);
        }
    }

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? scoped.toString() : null);
    }

    /** The cursor is the caller's to persist, through the store like every other durable value. */
    private static void writeCursor(ArtifactStore store, String cursor) throws IOException {
        byte[] content = (cursor == null ? "" : cursor).getBytes(StandardCharsets.UTF_8);
        Optional<ArtifactStore.Versioned> stored = store.readVersioned("cursors/test");
        store.writeVersioned("cursors/test", content, stored.map(ArtifactStore.Versioned::token).orElse(null));
    }

    private static String readCursor(ArtifactStore store) throws IOException {
        return store.readVersioned("cursors/test")
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8))
                .filter(cursor -> !cursor.isEmpty())
                .orElse(null);
    }

    // ---- the result type itself cannot express "incomplete but complete-looking" ----

    @Test
    void a_result_cannot_claim_exhaustion_while_carrying_a_cursor_or_truncation_without_one() {
        assertThatThrownBy(() -> new Traversal.Result(
                Traversal.Outcome.EXHAUSTED, Optional.of("root/a"), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Traversal.Result(
                Traversal.Outcome.TRUNCATED, Optional.empty(), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_non_positive_bound_is_a_caller_error_rather_than_an_unbounded_walk() {
        assertThatThrownBy(() -> PagedTreeWalk.bounded().entries(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PagedTreeWalk.bounded().steps(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PagedTreeWalk.bounded().depth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PagedTreeWalk.bounded().page(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
