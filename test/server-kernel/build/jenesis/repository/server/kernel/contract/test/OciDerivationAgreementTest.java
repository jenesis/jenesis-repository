package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.BlobRoots;
import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.oci.inventory.OciBlobLayout;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Which blobs does this OCI image keep alive" is now derived <b>once</b>, and this suite is what proves the collapse
 * did not change the answer.
 *
 * <p>It used to drive two derivations side by side - {@code OciFormat.references} in the core, the
 * {@link BlobReferences} seam a garbage collector's mark phase asks about every visited {@code oci/} key, and
 * {@code OciBlobLayout.blobHashes} in the inventory, which a retroactive hold marks and a name-enumeration screen
 * probes - because each walked the manifest JSON itself, with its own index expansion, its own digest validator and its
 * own hard-coded manifest cap. Two homes for one answer is a data-loss bug waiting for the day they disagree: a hash
 * the hold knows and the scan does not is a live blob the next collection pass condemns and then deletes out from
 * under a held image, and a hash the scan knows and the hold does not is a layer that keeps serving through a hold
 * that reports itself enforced. So it stopped deriving: it resolves the image's manifest hex and hands the
 * free seam the {@code oci/types/<hex>} key that names it.
 *
 * <p><b>A suite that proved agreement must now prove the collapse.</b> Every shape below still runs both sides over
 * the same stored bytes and still demands the same set - the legs are the regression net for the delegation, and they
 * would have caught the day the two walks drifted just as they now catch the day the delegation resolves the wrong key
 * or re-orders the answer. What changed is what "agreement" costs: the sets are equal because they are the same call,
 * so {@link #the_hold_side_hands_back_exactly_what_the_free_seam_lends()} states that directly, in order.
 *
 * <p><b>The one thing that is still two, and always will be.</b> The two sides carry opposite <em>failure postures</em>
 * by design, and the delegation is built around that rather than over it: a present-but-unenumerable manifest makes the
 * free seam throw (handing a deleter a short list is data loss) and makes the hold side degrade to the hash it is sure
 * of (a console browse, a KEV sweep and a release path must not fail wholesale over one corrupt legacy manifest). That
 * is exactly why the free seam had to name its refusal - {@link BlobReferences.Unresolvable}, "these bytes will never
 * parse" - before this call could be made at all: catching a bare {@code IOException} would have converted every store
 * hiccup into a silently under-enforced hold.
 */
class OciDerivationAgreementTest {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    /**
     * The installed lender for the {@code oci/} root - resolved through {@link BlobReferences#installed()},
     * the same static {@code MarkSweepGarbageCollectorProvider} resolves its lenders with, and since the earlier work the
     * same static (and the same filter) {@code OciBlobLayout} itself resolves its lender with, so this suite measures
     * the object the hold side really asks and not a hand-built stand-in.
     *
     * <p>Its absence is itself a failure worth naming: with no lender owning {@code oci/}, a mark pass sees only what
     * a tag pointer's body spells and every live image's config and layers are condemned and deleted - and the
     * hold side degrades to manifest-only. That layout is filtered out by its type: it declares the same
     * root, and since the earlier work it is a lender too, but it lends nothing because the free format owns the manifest dialect.
     */
    private static final BlobReferences FREE_OCI = BlobReferences.installed().stream()
            .filter(lender -> lender.blobRoots().contains("oci") && !(lender instanceof BlobRoots))
            .reduce((first, second) -> {
                throw new IllegalStateException("two lenders claim the oci/ root: " + first + ", " + second);
            })
            .orElseThrow(() -> new IllegalStateException("no installed format lends OCI references - a mark "
                    + "pass would see only tag-pointer bodies, and every live image's config and layers would be "
                    + "condemned and then deleted"));

    private final OciBlobLayout layout = new OciBlobLayout();

    @TempDir
    Path root;

    // --- the same set, every shape ---------------------------------------------------------------------------------

    @Test
    void a_plain_image_manifest() throws IOException {
        ArtifactStore store = store();
        String config = blob(store, "{\"os\":\"linux\"}");
        String layer = blob(store, "layer bytes");
        String manifest = blob(store, manifest(config, layer));
        sidecar(store, manifest);
        tag(store, "library/app", "1.0", manifest);

        agree(store, "library/app", "1.0", manifest);
    }

    @Test
    void an_image_index_and_each_sub_manifests_own_blobs() throws IOException {
        ArtifactStore store = store();
        String amdConfig = blob(store, "{\"arch\":\"amd64\"}");
        String amdLayer = blob(store, "amd64 layer");
        String amd = blob(store, manifest(amdConfig, amdLayer));
        String armConfig = blob(store, "{\"arch\":\"arm64\"}");
        String armLayer = blob(store, "arm64 layer");
        String arm = blob(store, manifest(armConfig, armLayer));
        String index = blob(store, index(amd, arm));
        sidecar(store, index);
        tag(store, "library/multi", "1.0", index);

        agree(store, "library/multi", "1.0", index);
        assertThat(layout.blobHashes("library/multi", "1.0", store))
                .as("the whole transitive image, not just the index")
                .containsExactlyInAnyOrder(index, amd, arm, amdConfig, amdLayer, armConfig, armLayer);
    }

    @Test
    void a_nested_index_expanded_by_both_through_a_work_list() throws IOException {
        ArtifactStore store = store();
        String config = blob(store, "{\"arch\":\"amd64\"}");
        String layer = blob(store, "amd64 layer");
        String leaf = blob(store, manifest(config, layer));
        String inner = blob(store, index(leaf));
        String outer = blob(store, index(inner));
        sidecar(store, outer);
        tag(store, "library/nested", "1.0", outer);

        agree(store, "library/nested", "1.0", outer);
        assertThat(layout.blobHashes("library/nested", "1.0", store))
                .containsExactlyInAnyOrder(outer, inner, leaf, config, layer);
    }

    @Test
    void a_layer_shared_by_two_sub_manifests_is_named_once_by_both() throws IOException {
        ArtifactStore store = store();
        String shared = blob(store, "the shared base layer");
        String amdConfig = blob(store, "{\"arch\":\"amd64\"}");
        String amd = blob(store, manifest(amdConfig, shared));
        String armConfig = blob(store, "{\"arch\":\"arm64\"}");
        String arm = blob(store, manifest(armConfig, shared));
        String index = blob(store, index(amd, arm));
        sidecar(store, index);
        tag(store, "library/shared", "1.0", index);

        agree(store, "library/shared", "1.0", index);
        assertThat(layout.blobHashes("library/shared", "1.0", store))
                .as("a digest reached twice is emitted once - the derivation dedupes through an emitted set")
                .containsExactlyInAnyOrder(index, amd, arm, amdConfig, armConfig, shared);
    }

    @Test
    void the_legacy_schema_one_fs_layers_shape() throws IOException {
        ArtifactStore store = store();
        String first = blob(store, "schema-1 layer one");
        String second = blob(store, "schema-1 layer two");
        String manifest = blob(store, "{\"schemaVersion\":1,\"fsLayers\":[{\"blobSum\":\"sha256:" + first
                + "\"},{\"blobSum\":\"sha256:" + second + "\"}]}");
        sidecar(store, manifest);
        tag(store, "library/legacy", "1.0", manifest);

        agree(store, "library/legacy", "1.0", manifest);
        assertThat(layout.blobHashes("library/legacy", "1.0", store))
                .containsExactlyInAnyOrder(manifest, first, second);
    }

    @Test
    void a_digest_only_reference_resolved_through_the_sidecar_key() throws IOException {
        ArtifactStore store = store();
        String config = blob(store, "{\"os\":\"linux\"}");
        String layer = blob(store, "layer bytes");
        String manifest = blob(store, manifest(config, layer));
        sidecar(store, manifest);                               // no tag pointer at all: the digest-only shape

        assertThat(layout.blobHashes("library/untagged", "sha256:" + manifest, store))
                .containsExactlyInAnyOrderElementsOf(FREE_OCI.references("oci/types/" + manifest, store))
                .containsExactlyInAnyOrder(manifest, config, layer);
    }

    @Test
    void a_manifest_whose_blob_is_already_gone_yields_the_manifest_hex_on_both_sides() throws IOException {
        // Residue: the tag pointer outlived its blob (an interrupted eviction, a collected manifest). Neither side may
        // invent a layer set, and neither may fail - "the blob is not there" is a complete answer, not a refusal, so
        // this is the one manifest-only answer that is NOT a degrade and must not WARN.
        ArtifactStore store = store();
        String manifest = blob(store, manifest(blob(store, "{}"), blob(store, "layer")));
        sidecar(store, manifest);
        tag(store, "library/residue", "1.0", manifest);
        store.delete("blobs/" + manifest);

        ListAppender<ILoggingEvent> logs = captureLayoutLogs();
        agree(store, "library/residue", "1.0", manifest);
        assertThat(layout.blobHashes("library/residue", "1.0", store)).containsExactly(manifest);
        assertThat(logs.list)
                .as("an absent blob is residue the free seam answers for cleanly, not a document that refuses to read")
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("manifest-only"));
    }

    // --- the collapse itself ----------------------------------------------------------------------------------------

    @Test
    void the_hold_side_hands_back_exactly_what_the_free_seam_lends() throws IOException {
        // The delegation, stated as the identity it now is: not "two walks that happen to agree today" but one call,
        // element for element and IN ORDER. Order is load-bearing on this side - blobHashes().getFirst() is the
        // /quarantine review handle's link target - so an equality that ignored it would not pin what the caller reads.
        ArtifactStore store = store();
        String amdConfig = blob(store, "{\"arch\":\"amd64\"}");
        String amd = blob(store, manifest(amdConfig, blob(store, "amd64 layer")));
        String armConfig = blob(store, "{\"arch\":\"arm64\"}");
        String arm = blob(store, manifest(armConfig, blob(store, "arm64 layer")));
        String index = blob(store, index(amd, arm));
        sidecar(store, index);
        tag(store, "library/collapse", "1.0", index);

        assertThat(layout.blobHashes("library/collapse", "1.0", store))
                .as("the hold side derives nothing of its own any more: it resolves the manifest hex and lends the "
                        + "free seam the oci/types/<hex> key that names it")
                .containsExactlyElementsOf(FREE_OCI.references("oci/types/" + index, store))
                .contains(index, amd, arm, amdConfig, armConfig);
        assertThat(layout.blobHashes("library/collapse", "1.0", store))
                .as("and a tag reference resolves to that same key rather than to a second derivation")
                .containsExactlyInAnyOrderElementsOf(FREE_OCI.references("oci/library/collapse/tags/1.0", store));
    }

    // --- the shared bound, now one number rather than two hard-coded copies -----------------------------------------

    @Test
    void a_manifest_just_under_the_shared_parse_bound_is_enumerated_by_both() throws IOException {
        // The manifest cap used to be written down twice - once in the free format's ingest bound, once as a constant
        // this layout MIRRORED by hand - and raising one alone enumerated a large-but-legal manifest for the collector
        // and not for the hold (a layer serving through a hold), or the reverse. There is one number now, and this leg
        // and the next bracket it from both sides so that a cap that moves in the free repository fails here rather
        // than in production.
        ArtifactStore store = store();
        String config = blob(store, "{\"os\":\"linux\"}");
        String layer = blob(store, "layer bytes");
        String manifest = blob(store, padded(manifest(config, layer), (4 * 1024 * 1024) - 1024));
        sidecar(store, manifest);
        tag(store, "library/big", "1.0", manifest);

        agree(store, "library/big", "1.0", manifest);
        assertThat(layout.blobHashes("library/big", "1.0", store))
                .containsExactlyInAnyOrder(manifest, config, layer);
    }

    @Test
    void a_manifest_past_the_shared_parse_bound_is_refused_by_both_in_their_own_direction() throws IOException {
        ArtifactStore store = store();
        String config = blob(store, "{\"os\":\"linux\"}");
        String layer = blob(store, "layer bytes");
        String manifest = blob(store, padded(manifest(config, layer), (4 * 1024 * 1024) + 1));
        sidecar(store, manifest);
        tag(store, "library/huge", "1.0", manifest);

        assertThatThrownBy(() -> FREE_OCI.references("oci/library/huge/tags/1.0", store))
                .as("past the bound the scan refuses rather than hand a deleter a short list")
                .isInstanceOf(BlobReferences.Unresolvable.class);
        assertThat(layout.blobHashes("library/huge", "1.0", store))
                .as("and the hold side catches exactly that refusal and degrades to the manifest it is sure of")
                .containsExactly(manifest);
    }

    /** A manifest document padded with an ignored field to exactly {@code size} bytes, so a leg can sit either side of
     *  the parse bound the one derivation enforces. */
    private static String padded(String manifest, int size) {
        String head = manifest.substring(0, manifest.length() - 1) + ",\"jenreg.pad\":\"";
        String tail = "\"}";
        return head + "x".repeat(size - head.length() - tail.length()) + tail;
    }

    // --- the one deliberate divergence, pinned so this suite never over-claims --------------------------------------

    @Test
    void the_one_deliberate_divergence_is_the_failure_posture() throws IOException {
        // A manifest blob that is present but is not a parseable manifest document. The free seam refuses (clause 3: a
        // short list handed to a deleter is a deleted live layer); the hold side catches that ONE named refusal,
        // degrades to the hash it is sure of and WARNs, because its callers are a console browse, a KEV sweep and a
        // release path. Both are correct for their caller, and neither is free to drift into the other's posture.
        ArtifactStore store = store();
        String manifest = blob(store, "this is not JSON at all");
        sidecar(store, manifest);
        tag(store, "library/corrupt", "1.0", manifest);

        assertThatThrownBy(() -> FREE_OCI.references("oci/library/corrupt/tags/1.0", store))
                .as("the reference scan fails the pass rather than under-report to a deleter")
                .isInstanceOf(BlobReferences.Unresolvable.class)
                .hasMessageContaining(manifest);
        ListAppender<ILoggingEvent> logs = captureLayoutLogs();
        assertThat(layout.blobHashes("library/corrupt", "1.0", store))
                .as("the hold marks what it can prove and keeps serving surfaces answering")
                .containsExactly(manifest);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("library/corrupt:1.0").contains("manifest-only");
        });
    }

    @Test
    void a_digest_rooted_unparseable_manifest_degrades_out_loud_too() throws IOException {
        // The degrade is SYMMETRIC since the earlier work, and it was not before. The hand-rolled walk WARNed only when the root
        // had been resolved from a TAG pointer, on the argument that only a tag pointer's target is contractually a
        // manifest; an image pulled by digest whose manifest no longer parses lost its layers from every hold and
        // enumeration in silence. The oci/types/<hex> sidecar is written for EVERY accepted manifest, so its target is
        // contractually a manifest too - the free seam raises for the root of either key, and this side says so either
        // way. The list is unchanged; being told is the whole change.
        ArtifactStore store = store();
        String manifest = blob(store, "\0\1 not a manifest document");
        sidecar(store, manifest);                               // digest-only: deliberately no tag pointer

        ListAppender<ILoggingEvent> logs = captureLayoutLogs();
        assertThat(layout.blobHashes("library/bydigest", "sha256:" + manifest, store))
                .containsExactly(manifest);
        assertThat(logs.list)
                .as("a digest-rooted image whose layers are no longer enumerable is an operator-actionable fact, not "
                        + "a silent one")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("library/bydigest:sha256:" + manifest).contains("manifest-only");
                });
    }

    // --- the seam the delegation rides on ---------------------------------------------------------------------------

    @Test
    void both_keys_that_name_one_manifest_lend_the_same_set() throws IOException {
        // The scan may visit either key for the same image, in either order, and must reach the same reference set:
        // the tag pointer (a tagged image) and the media-type sidecar (every accepted manifest, tagged or not). The
        // hold side hands over the SIDECAR key, so this is the leg that says that choice costs nothing.
        ArtifactStore store = store();
        String config = blob(store, "{\"os\":\"linux\"}");
        String layer = blob(store, "layer bytes");
        String manifest = blob(store, manifest(config, layer));
        sidecar(store, manifest);
        tag(store, "library/app", "1.0", manifest);

        assertThat(FREE_OCI.references("oci/library/app/tags/1.0", store))
                .containsExactlyInAnyOrderElementsOf(FREE_OCI.references("oci/types/" + manifest, store))
                .containsExactlyInAnyOrder(manifest, config, layer);
    }

    @Test
    void the_inventory_layout_is_filtered_out_of_its_own_lender_lookup() {
        // OciBlobLayout is itself a RepositoryFormat provider declaring the oci/ root, so BlobReferences.installed()
        // hands it back beside the free format. Were it not filtered out by type, blobHashes could resolve ITSELF as
        // the lender, inherit the empty default, and answer manifest-only for every image in the deployment - a hold
        // that marks nothing but the manifest while every layer keeps serving.
        assertThat(BlobReferences.installed())
                .anySatisfy(lender -> assertThat(lender).isInstanceOf(OciBlobLayout.class));
        assertThat(FREE_OCI).as("the lender the hold side resolves is the free format, never the layout itself")
                .isNotInstanceOf(BlobRoots.class);
    }

    @Test
    void every_root_a_lender_declares_is_read_by_the_reference_scan() {
        // A lender is only ever asked about keys the scan actually visits, and which roots are scanned is the
        // the inventory's answer (StoreRepositoryInventory.pointerRoots), while which roots are lent under is the
        // format's. A root on one list and not the other is a document nobody parses: its blobs are never marked, so
        // they are condemned and deleted while the image serves. Today the two agree because BlobRoots extends
        // BlobReferences and the free OCI format's single root is declared on this side too - and this leg is
        // what fails the day a release adds a lender the reference scan does not read.
        List<String> scanned = StoreRepositoryInventory.pointerRoots();
        for (BlobReferences lender : BlobReferences.installed()) {
            assertThat(scanned)
                    .as("%s lends references under %s, but the reference scan reads %s - a root that is "
                            + "lent under but never walked keeps nothing alive", lender.getClass().getName(),
                            lender.blobRoots(), scanned)
                    .containsAll(lender.blobRoots());
        }
    }

    // --- seeding ----------------------------------------------------------------------------------------------------

    /** Both sides over one image version, plus the ordering the hold side owes its caller. */
    private void agree(ArtifactStore store, String coordinate, String version, String manifest) throws IOException {
        List<String> hold = layout.blobHashes(coordinate, version, store);
        String pointer = "oci/" + coordinate + "/tags/" + version;
        assertThat(hold)
                .as("the hold side and the reference scan must name the same blobs for %s:%s, or one of them is "
                        + "marking a blob the other has deleted", coordinate, version)
                .containsExactlyInAnyOrderElementsOf(FREE_OCI.references(pointer, store))
                .containsExactlyInAnyOrderElementsOf(FREE_OCI.references("oci/types/" + manifest, store));
        assertThat(hold.getFirst())
                .as("the manifest hex leads the hold's list - it is the /quarantine review handle's link target, and "
                        + "withheldByAnotherAlias reads it as the per-image identity")
                .isEqualTo(manifest);
    }

    /** The WARN sink for {@link OciBlobLayout}'s one degrade, so "it degraded" and "it said so" are separate
     *  assertions rather than one hopeful one. */
    private static ListAppender<ILoggingEvent> captureLayoutLogs() {
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(OciBlobLayout.class.getName());
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String manifest(String config, String... layers) {
        return "{\"mediaType\":\"" + OCI_MANIFEST + "\",\"config\":{\"digest\":\"sha256:" + config + "\"},\"layers\":["
                + Arrays.stream(layers).map(layer -> "{\"digest\":\"sha256:" + layer + "\"}")
                        .collect(Collectors.joining(","))
                + "]}";
    }

    private static String index(String... manifests) {
        return "{\"mediaType\":\"application/vnd.oci.image.index.v1+json\",\"manifests\":["
                + Arrays.stream(manifests).map(manifest -> "{\"digest\":\"sha256:" + manifest + "\"}")
                        .collect(Collectors.joining(","))
                + "]}";
    }

    /** The media-type sidecar {@code OciManifests.ingest} writes for every accepted manifest - the key that makes a
     *  digest-only image reachable to the scan at all, and the key the hold side hands the free seam. */
    private static void sidecar(ArtifactStore store, String manifest) throws IOException {
        store.write("oci/types/" + manifest, new ByteArrayInputStream(OCI_MANIFEST.getBytes(StandardCharsets.UTF_8)));
    }

    private static void tag(ArtifactStore store, String name, String tag, String manifest) throws IOException {
        store.writeVersioned("oci/" + name + "/tags/" + tag,
                ("sha256:" + manifest).getBytes(StandardCharsets.UTF_8), null);
    }

    private static String blob(ArtifactStore store, String value) throws IOException {
        return store.writeBlob(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }
}
