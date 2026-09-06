package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStore.Listed;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Listings;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Listings}: the same drain as {@code Names}, with the size the backend's listing carried for each entry. */
class ListingsTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        for (int index = 0; index < 26; index++) {
            store.write("rows/" + String.format("%02d", index), new ByteArrayInputStream(new byte[index]));
        }
    }

    @Test
    void every_entry_comes_once_in_order_with_its_size_and_a_page_costs_one_scan() throws IOException {
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        List<Listed> all = new ArrayList<>();
        Listings listings = Listings.over(counting, "rows", 10);
        for (Listed entry = listings.next(); entry != null; entry = listings.next()) {
            all.add(entry);
        }
        assertThat(all).hasSize(26);
        assertThat(all.stream().map(Listed::key).toList()).isSorted().startsWith("rows/00").endsWith("rows/25");
        assertThat(all.get(7).size()).as("the size the listing carried, never a request of its own").hasValue(7);
        // 26 entries at ten a page is three pages, the third short: three page calls, never one per entry, and no
        // listing behind them.
        assertThat(counting.calls(FaultInjectingStore.Op.PAGE)).isEqualTo(3);
        assertThat(counting.calls(FaultInjectingStore.Op.LIST)).isZero();
        assertThat(Listings.over(store, "nothing/here", 10).next()).as("an empty level is exhausted at once").isNull();
    }
}
