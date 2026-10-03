package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DelimitedPages;

import static org.assertj.core.api.Assertions.assertThat;

/** A delimited listing's responses become one page of children in the order a page promises. */
class DelimitedPagesTest {

    private static List<String> page(String startAfter, int limit, List<List<String>> responses) {
        List<String> names = new ArrayList<>();
        DelimitedPages pages = new DelimitedPages(startAfter, limit,
                listed -> names.add(listed.key() + (listed.size().isPresent() ? "" : "/")));
        for (List<String> response : responses) {
            if (pages.page(response, (relative, name) -> relative.endsWith("/")
                    ? ArtifactStore.Listed.of(name) : ArtifactStore.Listed.of(name, 1L, Instant.EPOCH))) {
                return names;
            }
        }
        pages.finish();
        return names;
    }

    @Test
    void a_container_pages_before_a_sibling_that_extends_its_name() {
        // Raw key order puts the object app.txt before the grouped prefix app/, across two responses.
        assertThat(page("", 10, List.of(List.of("app.txt"), List.of("app/", "zoo"))))
                .containsExactly("app/", "app.txt", "zoo");
    }

    @Test
    void a_leaf_and_a_same_named_container_are_one_child_keeping_the_leaf() {
        assertThat(page("", 10, List.of(List.of("lib", "lib/")))).containsExactly("lib");
    }

    @Test
    void the_cursor_and_the_limit_bound_the_page() {
        assertThat(page("b", 2, List.of(List.of("a", "b", "b/", "c", "d", "e")))).containsExactly("c", "d");
    }
}
