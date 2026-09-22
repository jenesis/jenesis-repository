package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.HoldMarkers;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The servable-name coordinate/paging face the inventory grew in P-E2 - the seam the P-E3 console/search and REST browse
 * screen through so a held (withheld) or torn (blob-gone) name never appears in an enumeration, while a coordinate that is
 * merely a membership row with no blob stored still lists (the pinned ghost-coordinate contract).
 *
 * <p>Two methods are pinned. First {@link StoreRepositoryInventory#disclosable}: a {@code publish/}-namespace ecosystem is
 * held iff a {@code /quarantine<folder>} review pointer exists (screened through the STORE-FREE layout paths, opening no
 * blob), a blobs-namespace ecosystem is held iff any of its blob pointer keys resolves to a {@code withheld/<hash>} hash,
 * a ghost coordinate (recorded with no blob stored) stays disclosable under {@code HIDE_WITHHELD} with no blob stat, and
 * an ecosystem with no installed layout falls back to the durable, coordinate-keyed {@code holds/} record - still listed
 * when nothing holds it (the orphaned-format contract), withheld when something does. Second
 * {@link StoreRepositoryInventory#children(String, int, ServableNames.Policy)}: it pages one level, forwards a directory
 * unconditionally, screens a withheld leaf out under {@code HIDE_WITHHELD_AND_GONE}, and suppresses the reserved
 * {@code quarantine} review subtree at the root. A minimal discovered {@link InventoryTestScreen} gives the facade's own
 * {@code Publication} a real withheld chain (a leaf with a {@code publish/quarantine<path>} pointer is withheld), and a
 * pure blobs-namespace {@link InventoryTestBlobFormat} supplies the {@code blobKeys} leg.
 */
class InventoryDisclosureTest {

    private static final String PUBLISH_ECO = InventoryTestFormat.ECOSYSTEM;   // "test", a publish/-namespace layout
    private static final String BLOBS_ECO = InventoryTestBlobFormat.ECOSYSTEM; // "testblob", a blobs-namespace layout
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

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

    private Publication publication() {
        return new Publication(store);
    }

    // ---- disclosable: the coordinate face ----

    @Test
    void a_ghost_coordinate_recorded_with_no_blob_stored_stays_disclosable_under_hide_withheld() throws IOException {
        // The pinned membership contract: a coordinate recorded with NO blob stored at all must still be located and
        // disclosed by a search surface - HIDE_WITHHELD stats no blob, so the absence of one cannot hide it.
        inventory().record(PUBLISH_ECO, "ghost", "1.0.0", NOW);

        assertThat(inventory().disclosable(PUBLISH_ECO, "ghost", "1.0.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("a membership-only coordinate with no stored blob is disclosable (the ghost-coordinate contract)")
                .isTrue();
    }

    @Test
    void a_blobs_namespace_version_whose_tarball_hash_is_withheld_is_undisclosable_via_blob_keys() throws IOException {
        String hash = "c".repeat(64);
        // The version's single blobs-namespace pointer resolves to a hash the retroactive sweep marked withheld.
        store.writeVersioned(InventoryTestBlobFormat.blobKey("held-pkg", "2.0.0"),
                hash.getBytes(StandardCharsets.UTF_8), null);
        Withheld.mark(store, hash);

        assertThat(inventory().disclosable(BLOBS_ECO, "held-pkg", "2.0.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("a blobs-namespace version whose hash carries a withheld/<hash> marker is undisclosable")
                .isFalse();

        // Control: an un-marked sibling pointer discloses, so the assertion above is load-bearing on the marker, not on
        // the mere presence of a pointer.
        String live = "d".repeat(64);
        store.writeVersioned(InventoryTestBlobFormat.blobKey("live-pkg", "3.0.0"),
                live.getBytes(StandardCharsets.UTF_8), null);
        assertThat(inventory().disclosable(BLOBS_ECO, "live-pkg", "3.0.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("an un-marked blobs-namespace version discloses")
                .isTrue();
    }

    @Test
    void a_publish_namespace_version_with_a_quarantine_pointer_is_undisclosable_via_the_version_folder()
            throws IOException {
        inventory().record(PUBLISH_ECO, "held", "1.0.0", NOW);
        String artifact = InventoryTestFormat.path("held", "1.0.0");
        publication().link(artifact, "e".repeat(64));                 // the served leaf
        publication().link("/quarantine" + artifact, "e".repeat(64)); // the retroactive-hold review pointer

        assertThat(inventory().disclosable(PUBLISH_ECO, "held", "1.0.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("a publish/-namespace version whose folder carries a /quarantine review pointer is undisclosable")
                .isFalse();

        // Control: a sibling with no quarantine pointer discloses.
        inventory().record(PUBLISH_ECO, "held", "1.1.0", NOW);
        publication().link(InventoryTestFormat.path("held", "1.1.0"), "f".repeat(64));
        assertThat(inventory().disclosable(PUBLISH_ECO, "held", "1.1.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("an un-quarantined sibling version discloses")
                .isTrue();
    }

    /**
     * An ecosystem no installed layout places has neither of the two faces above: the {@code publish/}-namespace one
     * needs the version folder, the blobs-namespace one needs the version's content hashes, and both are resolved
     * through the owning format. So the screen falls back to the one statement of a hold that outlives that format -
     * the durable, coordinate-keyed {@code holds/} record.
     *
     * <p>Unheld, it still discloses, and that is the orphaned-format contract rather than a leftover: content whose
     * format module has left the deployment is still LISTED, rendered as an orphan and named as such, because nothing
     * is deleted or hidden by a module's absence. Held, it does not - reading "no layout" as "nothing withholds it"
     * would have let removing a format module publish, by name, every version its sweeps were holding.
     */
    @Test
    void an_unknown_ecosystem_with_no_installed_layout_discloses_unless_a_hold_record_stands() throws IOException {
        assertThat(inventory().disclosable("no-such-ecosystem", "whatever", "9.9.9",
                ServableNames.Policy.HIDE_WITHHELD))
                .as("no installed layout, and nothing holds it - the orphaned row is still named")
                .isTrue();

        store.writeVersioned(HoldMarkers.key("kev", "no-such-ecosystem", "whatever", "9.9.9"),
                "CVE-2026-0001".getBytes(StandardCharsets.UTF_8), null);

        assertThat(inventory().disclosable("no-such-ecosystem", "whatever", "9.9.9",
                ServableNames.Policy.HIDE_WITHHELD))
                .as("a retroactive hold record answers where no layout can, so the held name is not enumerated")
                .isFalse();
        assertThat(inventory().disclosable("no-such-ecosystem", "whatever", "9.9.9",
                ServableNames.Policy.HIDE_WITHHELD_AND_GONE))
                .as("both policies hide withheld, so the fallback resolves the same way under either")
                .isFalse();
    }

    /**
     * The third state, and the one that used to disclose: a layout IS installed for the ecosystem, so the fallback
     * above does not fire, and it can place nothing - so neither face examined anything and the screen had no
     * evidence either way. That silence used to be read as "nothing withholds it".
     *
     * <p>It is not a contrived arrangement. It is what a layout whose path mapping is configured per repository
     * answers when it is asked through the store-free overload - the overload this screen uses deliberately, so that
     * a search never opens a blob - and what any layout answers for a coordinate it cannot place. The consequence was
     * the same one the orphaned-format branch was written to close, arriving through a different door: a withheld
     * version publishing its own name through search, the browse, the lifecycle listing and the forwarding queue,
     * where a leaked name cannot be unleaked.
     *
     * <p>So the same coordinate-keyed backstop answers here. Unheld it still discloses, because "I could not tell"
     * must not become "hide everything" either - that would take a per-repository layout's whole catalogue out of
     * every enumeration.
     */
    @Test
    void an_installed_layout_that_can_place_nothing_falls_back_rather_than_disclosing() throws IOException {
        String eco = InventoryUnplaceableFormat.ECOSYSTEM;
        assertThat(RepositoryFormat.installed())
                .as("the premise: a layout IS installed for this ecosystem, so this is not the orphaned-format path")
                .anySatisfy(format -> {
                    assertThat(format).isInstanceOf(ArtifactLayout.class);
                    assertThat(((ArtifactLayout) format).ecosystem()).isEqualTo(eco);
                });

        assertThat(inventory().disclosable(eco, "whatever", "9.9.9", ServableNames.Policy.HIDE_WITHHELD))
                .as("nothing holds it, so an unplaceable version is still named - silence is not a hold either")
                .isTrue();

        store.writeVersioned(HoldMarkers.key("kev", eco, "whatever", "9.9.9"),
                "CVE-2026-0002".getBytes(StandardCharsets.UTF_8), null);

        assertThat(inventory().disclosable(eco, "whatever", "9.9.9", ServableNames.Policy.HIDE_WITHHELD))
                .as("a hold stands and no layout face could see it, so the name is not enumerated")
                .isFalse();
        assertThat(inventory().disclosable(eco, "whatever", "9.9.9", ServableNames.Policy.HIDE_WITHHELD_AND_GONE))
                .as("both policies hide withheld, so the fallback resolves the same way under either")
                .isFalse();
    }

    // ---- disclosableDisplay: the search-hit face (A26-F5 right-to-left multi-split) ----

    @Test
    void a_held_digest_pinned_oci_display_screens_via_the_second_colon_split() throws IOException {
        // The A26-F5 defect: an OCI digest-pinned row's display is <name>:sha256:<hex> (two colons). The old single
        // lastIndexOf(':') split mis-cut it to (<name>:sha256, <hex>) - a coordinate no ecosystem places - so the held
        // image FELL OPEN to true and leaked its name+digest through the Lucene search leg. Here the row is a
        // blobs-namespace member whose pointer hash is withheld; disclosableDisplay must probe the SECOND colon split,
        // place (<name>, sha256:<hex>) on the blobs ecosystem, and screen it to false.
        String digest = "b".repeat(64);
        String version = "sha256:" + digest;                       // the digest-reference "version" of an OCI row
        inventory().record(BLOBS_ECO, "myimage", version, NOW);    // the published/ membership row the scan enumerates
        store.writeVersioned(InventoryTestBlobFormat.blobKey("myimage", version),
                digest.getBytes(StandardCharsets.UTF_8), null);    // its single blobs-namespace pointer, body = hash
        Withheld.mark(store, digest);                              // the retroactive sweep marked that hash withheld

        assertThat(inventory().disclosableDisplay("myimage:" + version, ServableNames.Policy.HIDE_WITHHELD))
                .as("A26-F5: a held digest-pinned OCI display (<name>:sha256:<hex>) places on its second colon split "
                        + "and screens to false instead of mis-splitting and falling open")
                .isFalse();
    }

    @Test
    void an_unheld_digest_pinned_oci_display_discloses() throws IOException {
        // The load-bearing control for the case above: an identically-shaped digest display whose pointer hash carries
        // NO withheld marker discloses - so the false above is load-bearing on the marker, not on the two-colon shape.
        String digest = "c".repeat(64);
        String version = "sha256:" + digest;
        inventory().record(BLOBS_ECO, "cleanimage", version, NOW);
        store.writeVersioned(InventoryTestBlobFormat.blobKey("cleanimage", version),
                digest.getBytes(StandardCharsets.UTF_8), null);

        assertThat(inventory().disclosableDisplay("cleanimage:" + version, ServableNames.Policy.HIDE_WITHHELD))
                .as("an un-marked digest-pinned OCI display discloses (the shape alone never hides a row)")
                .isTrue();
    }

    @Test
    void a_maven_group_artifact_version_display_places_on_the_first_split_exactly_as_before() throws IOException {
        // Regression pin: Maven's display carries the coordinate's own colon (g:a:v). The first candidate of the
        // right-to-left loop IS the old single lastIndexOf(':') split, so (org.acme:lib, 1.0) places on the FIRST
        // split - byte-for-byte the pre-fix behavior for every colon-free-version ecosystem.
        inventory().record(PUBLISH_ECO, "org.acme:lib", "1.0", NOW);

        assertThat(inventory().disclosableDisplay("org.acme:lib:1.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("a g:a:v maven display places on the first (last-colon) split, unchanged from the single-split code")
                .isTrue();
    }

    @Test
    void a_plain_coordinate_version_display_screens_on_the_first_split_exactly_as_before() throws IOException {
        // Regression pin: the common single-colon coord:ver display screens through the first (and only) split exactly
        // as the single-split code did - here a held blobs-namespace row, so the screen is a true->false decision that
        // would break if the first candidate stopped being probed.
        String hash = "e".repeat(64);
        inventory().record(BLOBS_ECO, "plain", "9.9", NOW);
        store.writeVersioned(InventoryTestBlobFormat.blobKey("plain", "9.9"),
                hash.getBytes(StandardCharsets.UTF_8), null);
        Withheld.mark(store, hash);

        assertThat(inventory().disclosableDisplay("plain:9.9", ServableNames.Policy.HIDE_WITHHELD))
                .as("a plain coord:ver held row screens on the first split, unchanged from the single-split code")
                .isFalse();
    }

    @Test
    void a_display_no_ecosystem_places_on_any_split_stays_disclosable() throws IOException {
        // The ghost/uninstalled-format membership contract is preserved: a display no colon split places over any
        // published ecosystem discloses (membership is the only truth there). A held coordinate always carries a
        // published/ row, so the held case always places on some split - only genuinely absent displays reach here.
        assertThat(inventory().disclosableDisplay("totallyunplaced:x:y:z", ServableNames.Policy.HIDE_WITHHELD))
                .as("a display no ecosystem places on any right-to-left split stays disclosable (ghost contract)")
                .isTrue();
    }

    @Test
    void a_colon_less_bare_name_display_fails_closed_by_throwing() throws IOException {
        // a27/a2 F3: a colon-less display (a bare name with no :version) once fell through the right-to-left split loop
        // to the ghost-coordinate `return true` and disclosed unconditionally - a fail-open on the one input the
        // coordinate:version face is not meant to take. It now fails CLOSED by throwing: a name-level surface must
        // screen through ServableNames, not this face. No live caller passes a bare name (every caller supplies a
        // coordinate + ":" + version or an already-versioned group:name:version), so this closes a default, not a path.
        assertThatThrownBy(() -> inventory().disclosableDisplay("barename", ServableNames.Policy.HIDE_WITHHELD))
                .as("a colon-less bare-name display is rejected rather than disclosed unconditionally")
                .isInstanceOf(IllegalArgumentException.class);

        // Control: the same name carrying a :version is a valid coordinate:version display and screens normally (here an
        // unplaced display, which discloses by the ghost contract) - so the throw is load-bearing on the missing colon,
        // not on the name.
        assertThat(inventory().disclosableDisplay("barename:1.0", ServableNames.Policy.HIDE_WITHHELD))
                .as("the same name with a :version is a valid coordinate:version display and screens (discloses here)")
                .isTrue();
    }

    // ---- children: the screened paging face ----

    @Test
    void children_pages_forwards_a_folder_screens_a_withheld_leaf_and_suppresses_the_quarantine_root_child()
            throws IOException {
        // A directory child: any nested pointer makes "test" a folder under publish/.
        publication().link(InventoryTestFormat.path("tree", "1.0.0"), "1".repeat(64));

        // A servable leaf directly under publish/: a linked pointer whose blob is present.
        String servedHash = store.writeBlob(new ByteArrayInputStream("served".getBytes(StandardCharsets.UTF_8)));
        publication().link("/served", servedHash);

        // A withheld leaf directly under publish/: linked, but a /quarantine pointer withholds it through the chain
        // (via the discovered InventoryTestScreen). Its /quarantine link also creates the "quarantine" root child.
        publication().link("/withheldleaf", "2".repeat(64));
        publication().link("/quarantine/withheldleaf", "2".repeat(64));

        // A torn (blob-gone) leaf directly under publish/: linked to a hash with no stored blob.
        publication().link("/goneleaf", "3".repeat(64));

        List<String> page = inventory().children("", 100, ServableNames.Policy.HIDE_WITHHELD_AND_GONE).names();

        assertThat(page)
                .as("the folder and the servable leaf are forwarded; the withheld leaf, the blob-gone leaf and the "
                        + "quarantine review subtree are all screened out")
                .containsExactlyInAnyOrder("test", "served");
    }
}
