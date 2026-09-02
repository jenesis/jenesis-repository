package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flat sibling of the bounded subtree walk - the primitive the surfaces that are <em>not</em> tree walks ride: a
 * search window scanning a registry's id space, a version list under one coordinate, a revision's file set. It pins
 * the boundary cases a hand-rolled {@code while (true) { page; if (short) break; }} loop gets wrong: the cap met in the
 * middle of a buffered page, the cap met exactly on the last name of a short page (drained - exhausted) versus exactly
 * on the last name of a full page (unproven - truncated), the round-trip budget that bounds a scan whose window is
 * small but whose namespace is not, and the hostile name that must never be composed into a key.
 */
class BoundedChildrenTest {

    @Test
    void a_container_wider_than_the_page_is_drained_across_round_trips() throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 2_500; index++) {
            String name = String.format("pkg-%05d", index);
            store.seed("root/" + name + "/file");
            expected.add(name);
        }

        List<String> names = new ArrayList<>();
        Traversal.Result result = BoundedChildren.bounded().page(100).scan(store, "root", names::add);

        assertThat(result.exhausted()).isTrue();
        assertThat(result.cursor()).isEmpty();
        assertThat(result.delivered()).isEqualTo(2_500);
        assertThat(result.steps()).as("2500 names at 100 per round trip plus the short page that proves the end")
                .isEqualTo(26);
        assertThat(names).containsExactlyElementsOf(expected);
    }

    @Test
    void the_entry_cap_met_mid_page_truncates_and_the_continuation_resumes_at_the_boundary() throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 23; index++) {
            String name = String.format("id-%02d", index);
            store.seed("root/" + name);
            expected.add(name);
        }

        BoundedChildren children = BoundedChildren.bounded().entries(4).page(50);
        List<String> everything = new ArrayList<>();
        String cursor = null;
        Traversal.Result result;
        do {
            result = children.scan(store, "root", cursor, everything::add);
            cursor = result.cursor().orElse(null);
        } while (result.truncated());

        assertThat(everything).containsExactlyElementsOf(expected);
        assertThat(everything).doesNotHaveDuplicates();
        assertThat(result.exhausted()).isTrue();
    }

    @Test
    void the_entry_cap_met_on_a_short_page_still_proves_exhaustion() throws IOException {
        MemoryStore store = new MemoryStore();
        for (String name : List.of("a", "b", "c")) {
            store.seed("root/" + name);
        }

        List<String> names = new ArrayList<>();
        Traversal.Result result = BoundedChildren.bounded().entries(3).page(10).scan(store, "root", names::add);

        assertThat(names).containsExactly("a", "b", "c");
        assertThat(result.exhausted())
                .as("a short page proves the container is drained, so the cap met on it is not a truncation")
                .isTrue();
    }

    @Test
    void the_entry_cap_met_on_a_full_page_truncates_and_the_continuation_delivers_nothing() throws IOException {
        MemoryStore store = new MemoryStore();
        for (String name : List.of("a", "b", "c")) {
            store.seed("root/" + name);
        }

        BoundedChildren children = BoundedChildren.bounded().entries(3).page(3);
        List<String> names = new ArrayList<>();
        Traversal.Result truncated = children.scan(store, "root", names::add);

        assertThat(names).containsExactly("a", "b", "c");
        assertThat(truncated.truncated())
                .as("a full page ending on the cap proves nothing about what follows - never claim completeness")
                .isTrue();
        assertThat(truncated.cursor()).contains("root/c");

        List<String> more = new ArrayList<>();
        Traversal.Result exhausted = children.scan(store, "root", truncated.cursor().orElseThrow(), more::add);
        assertThat(more).isEmpty();
        assertThat(exhausted.exhausted()).isTrue();
    }

    @Test
    void the_round_trip_budget_bounds_a_scan_whose_window_is_small_but_whose_namespace_is_not() {
        MemoryStore store = new MemoryStore();
        for (int index = 0; index < 400; index++) {
            store.seed(String.format("root/id-%04d", index));
        }

        // The shape of a bounded search: every name is scanned (and filtered downstream), only a window is kept, so
        // the entry cap is not the bound that matters - the round-trip budget is.
        List<String> matched = new ArrayList<>();
        assertThatThrownBy(() -> BoundedChildren.bounded().page(10).steps(5)
                .scan(store, "root", name -> {
                    if (name.endsWith("7")) {
                        matched.add(name);
                    }
                }))
                .isInstanceOfSatisfying(TraversalException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(TraversalException.Reason.STEPS))
                .hasMessageContaining("more than 5 page round-trips");
    }

    @Test
    void an_absent_container_is_exhausted_rather_than_an_error() throws IOException {
        Traversal.Result result = BoundedChildren.bounded().scan(new MemoryStore(), "root", _ -> {
            throw new AssertionError("nothing may be delivered");
        });

        assertThat(result.exhausted()).isTrue();
        assertThat(result.delivered()).isZero();
    }

    @Test
    void a_traversal_probe_name_is_refused_rather_than_composed_into_a_key() {
        for (String planted : List.of("root/../etc", "root/./x", "root/a\\b")) {
            MemoryStore store = new MemoryStore();
            store.seed(planted);

            assertThatThrownBy(() -> BoundedChildren.bounded().scan(store, "root", _ -> {
            }))
                    .as("child of '%s'", planted)
                    .isInstanceOfSatisfying(TraversalException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(TraversalException.Reason.SEGMENT));
        }
    }

    @Test
    void the_scope_root_is_a_legal_prefix_and_its_child_names_are_already_keys() throws IOException {
        MemoryStore store = new MemoryStore();
        for (String space : List.of("blobs", "publish", "walks")) {
            store.seed(space + "/child");
        }

        BoundedChildren children = BoundedChildren.bounded().entries(2).page(2);
        List<String> names = new ArrayList<>();
        Traversal.Result truncated = children.scan(store, "", names::add);

        assertThat(names).containsExactly("blobs", "publish");
        assertThat(truncated.cursor()).as("a scope-root child's name already is its key").contains("publish");

        List<String> rest = new ArrayList<>();
        Traversal.Result exhausted = children.scan(store, "", truncated.cursor().orElseThrow(), rest::add);
        assertThat(rest).containsExactly("walks");
        assertThat(exhausted.exhausted()).isTrue();
    }

    @Test
    void a_cursor_that_is_not_an_immediate_child_key_is_rejected() {
        MemoryStore store = new MemoryStore();
        store.seed("root/a/b");

        assertThatThrownBy(() -> BoundedChildren.bounded().scan(store, "root", "other/a", _ -> {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not a child key");
        assertThatThrownBy(() -> BoundedChildren.bounded().scan(store, "root", "root/a/b", _ -> {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IMMEDIATE child key");
    }

    @Test
    void a_non_positive_bound_is_a_caller_error_rather_than_an_unbounded_listing() {
        assertThatThrownBy(() -> BoundedChildren.bounded().entries(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedChildren.bounded().steps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedChildren.bounded().page(-3)).isInstanceOf(IllegalArgumentException.class);
    }
}
