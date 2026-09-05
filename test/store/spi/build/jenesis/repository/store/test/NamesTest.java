package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Names;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Names}: a level's children pulled in the store's order over pages of a stated width, exhausted on a short
 * page, one level scan per page and never one per name; {@code select} keeps and renames; {@code concat} drains two
 * levels as one.
 */
class NamesTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default");
        for (int i = 0; i < 25; i++) {
            store.write("rows/" + String.format("%02d", i) + ".json", new ByteArrayInputStream(new byte[] {1}));
        }
        store.write("rows/readme.txt", new ByteArrayInputStream(new byte[] {1}));
        store.write("other/a", new ByteArrayInputStream(new byte[] {1}));
        store.write("other/b", new ByteArrayInputStream(new byte[] {1}));
    }

    private static List<String> drain(Names names) throws IOException {
        List<String> all = new ArrayList<>();
        for (String name = names.next(); name != null; name = names.next()) {
            all.add(name);
        }
        return all;
    }

    @Test
    void every_name_comes_once_in_order_over_pages_and_a_page_costs_one_scan() throws IOException {
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        List<String> all = drain(Names.over(counting, "rows", 10));
        assertThat(all).hasSize(26).isSorted().startsWith("00.json", "01.json").endsWith("readme.txt");
        // This decorator pages by listing, as the filesystem store scans a directory per page: 26 names at ten a
        // page is three pages, the third short, so three scans - never one per name.
        assertThat(counting.calls(FaultInjectingStore.Op.LIST)).isEqualTo(3);
        assertThat(Names.over(store, "rows", 10).next()).isNotNull();
        assertThat(drain(Names.over(store, "nothing/here", 10))).as("an empty level is exhausted at once").isEmpty();
    }

    @Test
    void select_keeps_renames_and_skips_and_concat_drains_two_levels_as_one() throws IOException {
        Names rows = Names.over(store, "rows", 10).select(name -> name.endsWith(".json")
                ? Optional.of(name.substring(0, name.length() - ".json".length()))
                : Optional.empty());
        List<String> ids = drain(rows);
        assertThat(ids).hasSize(25).startsWith("00").endsWith("24").doesNotContain("readme.txt");

        List<String> both = drain(Names.concat(Names.over(store, "other", 10),
                Names.over(store, "rows", 10).select(name -> name.startsWith("2") ? Optional.of(name) : Optional.empty())));
        assertThat(both).containsExactly("a", "b", "20.json", "21.json", "22.json", "23.json", "24.json");
    }
}
