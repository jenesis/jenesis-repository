package build.jenesis.repository.inventory.test;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page of an ecosystem's coordinates that start with a prefix - the face a lookup by name reads through. The
 * repository keeps its version documents sorted by coordinate, so the matches are one run of the listing: the page
 * starts at the run, stops at its end, resumes after the coordinate it ended on, and costs one listing page whatever
 * else the ecosystem holds.
 */
class CoordinatePageTest {

    private static final Instant PUBLISHED = Instant.parse("2026-09-30T00:00:00Z");

    @TempDir
    Path root;

    private FaultInjectingStore store;
    private final List<FaultInjectingStore.Op> operations = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore backing = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("releases");
        StoreRepositoryInventory seeding = new StoreRepositoryInventory(backing);
        for (String coordinate : List.of("org.acme:alpha", "org.acme:beta", "org.acmeish:gamma", "org.acm:delta",
                "@scope/widget", "zeta")) {
            seeding.record("npm", coordinate, "1.0.0", PUBLISHED);
        }
        store = FaultInjectingStore.wrap(backing).tracing((op, key) -> operations.add(op));
    }

    @Test
    void a_page_is_the_run_of_names_starting_with_the_prefix_and_resumes_after_its_last() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);

        StoreRepositoryInventory.CoordinatePage first = inventory.coordinates("npm", "org.acme", null, 2);
        assertThat(first.coordinates()).containsExactly("org.acme:alpha", "org.acme:beta");
        assertThat(first.next()).as("a full page names where the next resumes").isEqualTo("org.acme:beta");

        StoreRepositoryInventory.CoordinatePage second = inventory.coordinates("npm", "org.acme", first.next(), 2);
        assertThat(second.coordinates()).containsExactly("org.acmeish:gamma");
        assertThat(second.next()).as("the run ended, so nothing remains").isNull();

        assertThat(inventory.coordinates("npm", "@scope/", null, 10).coordinates())
                .as("a coordinate carrying reserved characters matches as written").containsExactly("@scope/widget");
        assertThat(inventory.coordinates("npm", "", null, 10).coordinates()).as("an empty prefix is every name")
                .hasSize(6);
        assertThat(inventory.coordinates("npm", "nothing", null, 10).coordinates()).isEmpty();
    }

    @Test
    void a_page_costs_one_listing_and_no_document_read() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        operations.clear();

        inventory.coordinates("npm", "org.acme:", null, 10);

        assertThat(operations).as("one page of the listing, whatever else the ecosystem holds")
                .containsOnly(FaultInjectingStore.Op.PAGE).hasSize(1);
    }
}
