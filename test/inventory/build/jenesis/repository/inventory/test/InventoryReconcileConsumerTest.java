package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.InventoryReconcileConsumer;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** The reconcile as a listener: one pass over the pointer, inventory and derived families does what the three walks
 *  of its own did. */
class InventoryReconcileConsumerTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
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

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void one_pass_restores_the_forward_leg_removes_the_reverse_legs_orphan_and_sweeps_a_derived_row() throws IOException {
        link("half-published", "1.0.0");                                    // forward: its section was never written
        link("half-evicted", "1.0.0");
        inventory().record(InventoryTestFormat.path("half-evicted", "1.0.0"), NOW);
        store.delete("publish" + InventoryTestFormat.path("half-evicted", "1.0.0"));   // reverse: an orphan section
        store.write("downloaded/" + ECO + "/ghost/1.0.0",                     // derived: a legacy marker of nothing published
                new ByteArrayInputStream(NOW.toString().getBytes(StandardCharsets.UTF_8)));
        InventoryReconcileConsumer consumer = new InventoryReconcileConsumer();
        assertThat(consumer.families()).containsExactlyInAnyOrder(
                InventoryReconcileConsumer.Family.POINTERS, InventoryReconcileConsumer.Family.INVENTORY,
                InventoryReconcileConsumer.Family.DERIVED);

        Optional<WalkPass> pass = RebuildPass.run(WALK, store, new Publication(store), roots(), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(inventory().coordinates())
                .extracting(StoreRepositoryInventory.Coordinate::coordinate)
                .as("the served pointer's facts are restored; the orphan section is gone")
                .containsExactly("half-published");
        assertThat(inventory().lastDownloaded(ECO, "ghost", "1.0.0")).as("the orphan marker is swept").isEmpty();
        assertThat(RebuildPass.failed(store)).isEmpty();
    }

    @Test
    void a_second_pass_over_a_converged_store_changes_nothing() throws IOException {
        link("stable", "1.0.0");
        inventory().record(InventoryTestFormat.path("stable", "1.0.0"), NOW);
        InventoryReconcileConsumer consumer = new InventoryReconcileConsumer();
        RebuildPass.run(WALK, store, new Publication(store), roots(), List.of(consumer));
        Map<String, byte[]> before = snapshot();

        RebuildPass.run(WALK, store, new Publication(store), roots(), List.of(consumer));

        Map<String, byte[]> after = snapshot();
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((key, body) -> assertThat(after.get(key)).as(key).isEqualTo(body));
    }

    private static RebuildPass.Roots roots() {
        return new RebuildPass.Roots(StoreRepositoryInventory.pointerRoots(),
                List.of(StoreRepositoryInventory.publishedRoot()), List.of("blobs"),
                StoreRepositoryInventory.derivedRoots());
    }

    /** Every object outside the walk's own pass state, by key. */
    private Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> objects = new TreeMap<>();
        collect("", objects);
        objects.keySet().removeIf(key -> key.startsWith("walks/"));
        return objects;
    }

    private void collect(String prefix, Map<String, byte[]> into) throws IOException {
        for (String child : store.list(prefix)) {
            String key = prefix.isEmpty() ? child : prefix + "/" + child;
            Optional<ArtifactStore.Versioned> leaf = store.readVersioned(key);
            if (leaf.isPresent()) {
                into.put(key, leaf.get().content());
            } else {
                collect(key, into);
            }
        }
    }

    private void link(String coordinate, String version) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(
                (coordinate + "@" + version).getBytes(StandardCharsets.UTF_8)));
        publication.link(InventoryTestFormat.path(coordinate, version), hash);
    }
}
