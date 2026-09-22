package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconcile sweep - the §4/§5 convergence backstop - rebuilds the published set from the live {@code publish/}
 * pointer tree in both directions over the shared artifact walk. The forward leg recreates a served pointer's missing
 * published section (a crashed publish that linked the pointer but never recorded the fact), the reverse leg removes a
 * section whose pointers are gone (a crashed eviction's residue), the derived leg sweeps a download marker of a
 * no-longer-published version, a re-run over a converged store restores and removes nothing (idempotent), and a store
 * drifted in both directions at once converges in a single pass. Driven end to end through the store SPI with the
 * shared filesystem-backed walk; no framework.
 */
class ReconcileTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofHours(6));
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
    void the_forward_leg_restores_a_served_pointers_missing_published_section() throws IOException {
        // A crashed publish: the pointer was linked (the artifact serves) but the published fact was never recorded,
        // so the release is invisible to retention, garbage collection and the search/license index.
        link("crashed", "1.0.0");
        assertThat(inventory().coordinates()).as("the un-recorded pointer is invisible to enumeration").isEmpty();

        StoreRepositoryInventory.Reconciliation result = inventory().reconcile(WALK, NOW);

        assertThat(result.restored()).as("the missing published section is rebuilt from the live pointer").isEqualTo(1);
        assertThat(result.removed()).isZero();
        assertThat(inventory().coordinates())
                .extracting(StoreRepositoryInventory.Coordinate::coordinate,
                        StoreRepositoryInventory.Coordinate::version)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("crashed", "1.0.0"));
        assertThat(inventory().releases().iterator().next().published())
                .as("the section is timestamped at the conservative reconcile instant").isEqualTo(NOW);
    }

    @Test
    void the_reverse_leg_removes_an_orphan_section_whose_pointers_are_gone() throws IOException {
        // A crashed eviction: the published section survives but every serving pointer is gone.
        link("orphan", "1.0.0");
        inventory().record(InventoryTestFormat.path("orphan", "1.0.0"), NOW);
        assertThat(inventory().coordinates()).hasSize(1);
        unlink("orphan", "1.0.0");
        assertThat(inventory().coordinates()).as("the orphan section still drifts in the enumeration").hasSize(1);

        StoreRepositoryInventory.Reconciliation result = inventory().reconcile(WALK, LATER);

        assertThat(result.removed()).as("the orphan section is removed").isEqualTo(1);
        assertThat(result.restored()).isZero();
        assertThat(inventory().coordinates()).as("the drift is gone").isEmpty();
    }

    @Test
    void the_derived_leg_sweeps_a_download_marker_of_a_no_longer_published_version() throws IOException {
        // A download marker in the layout from before the downloads section: the sidecar of a version that is
        // neither published nor serving - the residue of an eviction that removed the release but not its derived
        // rows. The derived leg still sweeps the legacy row; a document's downloads section goes with the document.
        store.write("downloaded/" + ECO + "/ghost/1.0.0", new ByteArrayInputStream(NOW.toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(inventory().lastDownloaded(ECO, "ghost", "1.0.0")).contains(NOW);

        StoreRepositoryInventory.Reconciliation result = inventory().reconcile(WALK, LATER);

        assertThat(result.derived()).as("the orphan download marker is swept").isEqualTo(1);
        assertThat(inventory().lastDownloaded(ECO, "ghost", "1.0.0")).as("the marker is gone").isEmpty();
    }

    @Test
    void a_re_run_over_a_converged_store_restores_and_removes_nothing() throws IOException {
        link("stable", "1.0.0");
        inventory().record(InventoryTestFormat.path("stable", "1.0.0"), NOW);

        StoreRepositoryInventory.Reconciliation first = inventory().reconcile(WALK, LATER);
        assertThat(first).isEqualTo(new StoreRepositoryInventory.Reconciliation(0, 0, 0));

        StoreRepositoryInventory.Reconciliation second = inventory().reconcile(WALK, LATER);
        assertThat(second).as("idempotent: a second pass is a no-op").isEqualTo(
                new StoreRepositoryInventory.Reconciliation(0, 0, 0));
        assertThat(inventory().coordinates()).hasSize(1);
    }

    @Test
    void a_store_drifted_in_both_directions_converges_in_one_pass() throws IOException {
        // One release crashed mid-publish (pointer, no section); another crashed mid-eviction (section, no pointer).
        link("half-published", "1.0.0");                                     // forward: needs its section rebuilt
        link("half-evicted", "1.0.0");
        inventory().record(InventoryTestFormat.path("half-evicted", "1.0.0"), NOW);
        unlink("half-evicted", "1.0.0");                                     // reverse: its section is now an orphan

        StoreRepositoryInventory.Reconciliation result = inventory().reconcile(WALK, LATER);

        assertThat(result.restored()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(inventory().coordinates())
                .extracting(StoreRepositoryInventory.Coordinate::coordinate)
                .containsExactly("half-published");
    }

    /** Link a served pointer for a coordinate version, storing a small blob behind it as a real publish would. */
    private void link(String coordinate, String version) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(
                (coordinate + "@" + version).getBytes(StandardCharsets.UTF_8)));
        publication.link(InventoryTestFormat.path(coordinate, version), hash);
    }

    /** Delete the served pointer, leaving any recorded published section behind - a crashed eviction's residue. */
    private void unlink(String coordinate, String version) throws IOException {
        store.delete("publish" + InventoryTestFormat.path(coordinate, version));
    }
}
