package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.VersionRemoval;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.format.OciTagIndex;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DELETE} of a manifest and of a tag, over the composition that carries it out: the real OCI format, the
 * store-backed inventory whose eviction is the product's one removal, the OCI layout that tells it which keys a version
 * occupies, and the mark-and-sweep collector that reclaims what the removal released.
 *
 * <p>That it is the one removal is what the inventory's rows show: the removed version's published row goes, as a
 * retention eviction takes it, and its tag leaves the tag list through the listing observer the eviction notifies. A
 * format deleting its own pointers would pass the pull assertions and fail those.
 */
class OciDeleteTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private RepositoryFormat format;
    private StoreRepositoryInventory inventory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        format = RepositoryFormat.installed().stream().filter(installed -> installed.name().equals("oci"))
                .findFirst().orElseThrow();
        inventory = new StoreRepositoryInventory(store);
    }

    @Test
    void the_removal_is_the_inventory_s() {
        assertThat(VersionRemoval.installed().supported()).isTrue();
    }

    @Test
    void deleting_a_tag_removes_that_tag_alone() throws IOException {
        String manifest = image("app", "one", "1.0", "latest");

        Exchange delete = send("DELETE", "/v2/app/manifests/1.0");
        assertThat(delete.status()).isEqualTo(202);
        assertThat(delete.audited()).containsExactly("artifact.delete app:1.0");

        assertThat(send("GET", "/v2/app/manifests/1.0").status()).isEqualTo(404);
        assertThat(send("GET", "/v2/app/manifests/latest").status()).as("the other tag still serves").isEqualTo(200);
        assertThat(send("GET", "/v2/app/manifests/sha256:" + manifest).status())
                .as("and so does the manifest, which a live tag still names").isEqualTo(200);
        assertThat(new String(send("GET", "/v2/app/tags/list").body(), StandardCharsets.UTF_8))
                .contains("\"latest\"").doesNotContain("\"1.0\"");
        assertThat(inventory.release("oci", "app", "1.0")).as("the eviction took the version's row").isEmpty();
        assertThat(inventory.release("oci", "app", "latest")).isPresent();
    }

    @Test
    void deleting_the_last_tag_retires_the_image_and_a_collection_reclaims_what_nothing_else_holds()
            throws IOException {
        byte[] shared = "a base layer both images carry".getBytes(StandardCharsets.UTF_8);
        String retired = image("app", "app's own", "1.0");
        String kept = image("other", "other's own", "1.0");
        String sharedHex = blob("other", shared);
        String retiredLayer = hex(("layer of app's own").getBytes(StandardCharsets.UTF_8));
        String keptLayer = hex(("layer of other's own").getBytes(StandardCharsets.UTF_8));
        String withShared = push("app", "2.0", manifest(hex("config of app's own".getBytes(StandardCharsets.UTF_8)),
                retiredLayer, sharedHex));
        String keptWithShared = push("other", "2.0", manifest(hex("config of other's own".getBytes(StandardCharsets.UTF_8)),
                keptLayer, sharedHex));

        assertThat(send("DELETE", "/v2/app/manifests/1.0").status()).isEqualTo(202);
        assertThat(send("DELETE", "/v2/app/manifests/2.0").status()).isEqualTo(202);
        assertThat(send("GET", "/v2/app/manifests/sha256:" + retired).status())
                .as("the last tag gone, the manifest no longer resolves").isEqualTo(404);

        collect();
        collect();
        assertThat(store.exists("blobs/" + retired)).as("the retired manifest is reclaimed").isFalse();
        assertThat(store.exists("blobs/" + withShared)).isFalse();
        assertThat(store.exists("blobs/" + retiredLayer)).as("and the layer only it carried").isFalse();
        assertThat(store.exists("blobs/" + sharedHex)).as("a layer another image carries stays").isTrue();
        assertThat(store.exists("blobs/" + keptLayer)).isTrue();
        assertThat(send("GET", "/v2/other/manifests/1.0").status()).isEqualTo(200);
        assertThat(send("GET", "/v2/other/manifests/sha256:" + keptWithShared).status()).isEqualTo(200);
        assertThat(send("GET", "/v2/other/blobs/sha256:" + sharedHex).status()).isEqualTo(200);
        assertThat(store.exists("blobs/" + kept)).isTrue();
    }

    @Test
    void deleting_a_manifest_by_digest_removes_it_and_every_tag_naming_it() throws IOException {
        String manifest = image("app", "one", "1.0", "latest");
        image("app", "two", "2.0");

        Exchange delete = send("DELETE", "/v2/app/manifests/sha256:" + manifest);
        assertThat(delete.status()).isEqualTo(202);
        assertThat(delete.audited()).containsExactly("artifact.delete app@sha256:" + manifest);

        assertThat(send("GET", "/v2/app/manifests/1.0").status()).isEqualTo(404);
        assertThat(send("GET", "/v2/app/manifests/latest").status()).isEqualTo(404);
        assertThat(send("GET", "/v2/app/manifests/sha256:" + manifest).status()).isEqualTo(404);
        assertThat(send("GET", "/v2/app/manifests/2.0").status()).as("a tag naming another manifest stays")
                .isEqualTo(200);
        assertThat(inventory.release("oci", "app", "1.0")).isEmpty();
        assertThat(inventory.release("oci", "app", "latest")).isEmpty();
        assertThat(inventory.release("oci", "app", "2.0")).isPresent();
    }

    @Test
    void a_manifest_pushed_by_digest_alone_is_removed_by_its_digest_and_by_nothing_else() throws IOException {
        String config = blob("app", "config".getBytes(StandardCharsets.UTF_8));
        String layer = blob("app", "digest only".getBytes(StandardCharsets.UTF_8));
        byte[] body = manifest(config, layer);
        String manifest = push("app", "sha256:" + hex(body), body);

        // Retention's eviction of the same version takes nothing: an index a live tag serves may name this manifest.
        inventory.evict(inventory.release("oci", "app", "sha256:" + manifest).orElseThrow());
        assertThat(send("GET", "/v2/app/manifests/sha256:" + manifest).status()).isEqualTo(200);

        assertThat(send("DELETE", "/v2/app/manifests/sha256:" + manifest).status()).isEqualTo(202);
        assertThat(send("GET", "/v2/app/manifests/sha256:" + manifest).status()).isEqualTo(404);
    }

    @Test
    void a_digest_another_image_still_tags_stays_servable_there() throws IOException {
        String config = blob("app", "config".getBytes(StandardCharsets.UTF_8));
        String layer = blob("app", "layer".getBytes(StandardCharsets.UTF_8));
        byte[] body = manifest(config, layer);
        String manifest = push("app", "1.0", body);
        blob("other", "config".getBytes(StandardCharsets.UTF_8));
        blob("other", "layer".getBytes(StandardCharsets.UTF_8));
        push("other", "1.0", body);

        assertThat(send("DELETE", "/v2/app/manifests/sha256:" + manifest).status()).isEqualTo(202);
        assertThat(send("GET", "/v2/app/manifests/1.0").status()).isEqualTo(404);
        assertThat(send("GET", "/v2/other/manifests/1.0").status()).isEqualTo(200);
        assertThat(send("GET", "/v2/other/manifests/sha256:" + manifest).status())
                .as("the manifest record is the repository's, and another image's tag still names it")
                .isEqualTo(200);
    }

    @Test
    void a_pinned_version_is_refused_and_nothing_is_removed() throws IOException {
        String manifest = image("app", "one", "1.0", "latest");
        inventory.pin("oci", "app", "latest");

        Exchange tag = send("DELETE", "/v2/app/manifests/latest");
        assertThat(tag.status()).isEqualTo(403);
        assertThat(new String(tag.body(), StandardCharsets.UTF_8)).contains("\"code\":\"DENIED\"").contains("pinned");
        Exchange digest = send("DELETE", "/v2/app/manifests/sha256:" + manifest);
        assertThat(digest.status()).as("nor by the digest a pinned tag names").isEqualTo(403);
        assertThat(tag.audited()).isEmpty();

        assertThat(send("GET", "/v2/app/manifests/latest").status()).isEqualTo(200);
        assertThat(send("GET", "/v2/app/manifests/1.0").status()).as("not even the unpinned tag").isEqualTo(200);
    }

    @Test
    void a_held_or_absent_version_is_unknown_as_a_pull_of_it_would_be() throws IOException {
        String manifest = image("app", "one", "1.0");
        Withheld.mark(store, manifest);

        Exchange held = send("DELETE", "/v2/app/manifests/1.0");
        assertThat(held.status()).isEqualTo(404);
        assertThat(new String(held.body(), StandardCharsets.UTF_8)).contains("\"code\":\"MANIFEST_UNKNOWN\"");
        assertThat(store.exists("oci/app/tags/1.0")).as("the held version stays with its reviewer").isTrue();

        Withheld.clear(store, manifest, Known.absent());
        assertThat(send("DELETE", "/v2/app/manifests/9.9").status()).isEqualTo(404);
        assertThat(send("DELETE", "/v2/app/manifests/sha256:" + "e".repeat(64)).status()).isEqualTo(404);
        assertThat(send("DELETE", "/v2/app/manifests/sha256:nothex").status()).isEqualTo(400);
    }

    @Test
    void a_deleted_referrer_leaves_its_subject_s_index() throws IOException {
        String subject = image("app", "one", "1.0");
        byte[] body = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"application/spdx+json\",\"config\":{\"mediaType\":"
                + "\"application/vnd.oci.empty.v1+json\",\"size\":2,\"digest\":\"sha256:"
                + blob("app", "{}".getBytes(StandardCharsets.UTF_8)) + "\"},\"layers\":[],\"subject\":{\"mediaType\":"
                + "\"application/vnd.oci.image.manifest.v1+json\",\"size\":1,\"digest\":\"sha256:" + subject + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        String referrer = push("app", "sha256:" + hex(body), body);
        assertThat(new String(send("GET", "/v2/app/referrers/sha256:" + subject).body(), StandardCharsets.UTF_8))
                .contains(referrer);

        assertThat(send("DELETE", "/v2/app/manifests/sha256:" + referrer).status()).isEqualTo(202);
        assertThat(new String(send("GET", "/v2/app/referrers/sha256:" + subject).body(), StandardCharsets.UTF_8))
                .doesNotContain(referrer);
        assertThat(store.isEmpty("oci/app/.manifests/" + subject + "/referrers")).isTrue();
    }

    @Test
    void the_digest_to_tags_index_follows_a_retag_and_a_delete() throws IOException {
        String first = image("app", "one", "1.0", "latest");
        String second = image("app", "two", "latest");

        assertThat(OciTagIndex.current(store, first)).as("a re-tag moves the tag off the first manifest")
                .containsExactly(new OciTagIndex.Tag("app", "1.0"));
        assertThat(OciTagIndex.entered(store, first)).as("and retires its entry there")
                .containsExactly(new OciTagIndex.Tag("app", "1.0"));
        assertThat(OciTagIndex.current(store, second)).containsExactly(new OciTagIndex.Tag("app", "latest"));

        assertThat(send("DELETE", "/v2/app/manifests/sha256:" + first).status()).isEqualTo(202);
        assertThat(OciTagIndex.entered(store, first)).as("a delete retires the entries of the tags it removed")
                .isEmpty();
        assertThat(new String(send("GET", "/v2/_catalog").body(), StandardCharsets.UTF_8))
                .as("the index is no image").doesNotContain(".tagged");
    }

    @Test
    void a_tag_the_index_never_heard_of_keeps_its_manifest_s_record() throws IOException {
        String manifest = image("app", "one", "1.0", "latest");
        // A store written before the index existed: its tags have no entries.
        for (OciTagIndex.Tag tag : OciTagIndex.entered(store, manifest)) {
            OciTagIndex.retire(store, tag.pointer(), manifest);
        }

        inventory.evict(inventory.release("oci", "app", "latest").orElseThrow());

        assertThat(store.exists("oci/.types/" + manifest))
                .as("an index that cannot speak for the tag's siblings keeps the record they may still need").isTrue();
        assertThat(send("GET", "/v2/app/manifests/1.0").status()).isEqualTo(200);
    }

    @Test
    void an_image_may_be_named_what_the_format_s_own_spaces_are_named_and_none_may_begin_with_a_dot()
            throws IOException {
        String types = image("types", "an image called types", "1.0");
        image("uploads", "an image called uploads", "1.0");
        assertThat(send("GET", "/v2/types/manifests/1.0").status()).isEqualTo(200);
        assertThat(new String(send("GET", "/v2/uploads/tags/list").body(), StandardCharsets.UTF_8)).contains("1.0");
        assertThat(send("DELETE", "/v2/types/manifests/sha256:" + types).status())
                .as("and removed like any other, its record beside the format's own").isEqualTo(202);

        Exchange dotted = new Exchange("PUT", "/v2/.types/manifests/1.0", manifest("a".repeat(64)),
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(dotted, store);
        assertThat(dotted.status()).as("a name the grammar refuses, which is what keeps the format's spaces apart")
                .isGreaterThanOrEqualTo(400);
        assertThat(store.exists("oci/.types/tags/1.0")).isFalse();
    }

    @Test
    void a_delete_reads_the_same_however_many_tags_the_repository_holds() throws IOException {
        assertThat(deleteReads(10)).as("the reads of the same deletes over ten other tags and over a thousand")
                .isEqualTo(deleteReads(1_000));
    }

    /** The store reads a delete by tag of a tag with a sibling, and a delete by digest of an image with one tag, cost
     *  in a repository that also holds {@code others} tags of another image. */
    private int deleteReads(int others) throws IOException {
        ArtifactStore plain = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.resolve("bound-" + others).toString() : null);
        store = plain;
        inventory = new StoreRepositoryInventory(store);
        image("app", "one", "1.0", "latest");
        String solo = image("solo", "solo", "1.0");
        String config = blob("bulk", "bulk config".getBytes(StandardCharsets.UTF_8));
        String layer = blob("bulk", "bulk layer".getBytes(StandardCharsets.UTF_8));
        byte[] bulk = manifest(config, layer);
        for (int index = 0; index < others; index++) {
            push("bulk", "t" + index, bulk);
        }
        FaultInjectingStore counted = FaultInjectingStore.wrap(plain);
        store = counted;
        assertThat(send("DELETE", "/v2/app/manifests/1.0").status()).isEqualTo(202);
        assertThat(send("DELETE", "/v2/solo/manifests/sha256:" + solo).status()).isEqualTo(202);
        assertThat(send("GET", "/v2/solo/manifests/1.0").status()).isEqualTo(404);
        int reads = 0;
        for (FaultInjectingStore.Op op : List.of(FaultInjectingStore.Op.READ, FaultInjectingStore.Op.OPEN,
                FaultInjectingStore.Op.READ_VERSIONED, FaultInjectingStore.Op.EXISTS, FaultInjectingStore.Op.LIST,
                FaultInjectingStore.Op.PAGE, FaultInjectingStore.Op.SIZE)) {
            reads += counted.calls(op);
        }
        return reads;
    }

    // ---- helpers ----

    /** An image of one config and one layer derived from {@code seed}, pushed under each tag; its manifest hex. */
    private String image(String name, String seed, String... tags) throws IOException {
        String config = blob(name, ("config of " + seed).getBytes(StandardCharsets.UTF_8));
        String layer = blob(name, ("layer of " + seed).getBytes(StandardCharsets.UTF_8));
        String hex = null;
        for (String tag : tags) {
            hex = push(name, tag, manifest(config, layer));
        }
        return hex;
    }

    private static byte[] manifest(String config, String... layers) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (String layer : layers) {
            joiner.add("{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":1,"
                    + "\"digest\":\"sha256:" + layer + "\"}");
        }
        return ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":1,"
                + "\"digest\":\"sha256:" + config + "\"},\"layers\":" + joiner + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Push a manifest and record its version as a publish does, so its removal has a row to take. */
    private String push(String name, String reference, byte[] body) throws IOException {
        Exchange put = new Exchange("PUT", "/v2/" + name + "/manifests/" + reference, body,
                Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        inventory.record("oci", name, reference, clock.instant());
        return hex(body);
    }

    private String blob(String name, byte[] content) throws IOException {
        String hex = hex(content);
        Exchange post = new Exchange("POST", "/v2/" + name + "/blobs/uploads/?digest=sha256:" + hex, content, Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
        return hex;
    }

    private Exchange send(String method, String path) throws IOException {
        Exchange exchange = new Exchange(method, path, new byte[0], Map.of());
        format.handle(exchange, store);
        return exchange;
    }

    /** One pass of the collector a deployment resolves, with no wall-clock grace: a blob is condemned by one pass
     *  and deleted by the next. */
    private void collect() throws IOException {
        GcPlan plan = GarbageCollectorProvider.resolve(key -> "gc.grace".equals(key) ? "PT0S" : null).orElseThrow()
                .collect(store, Known.known(List.of("publish", "oci")), Instant.now());
        assertThat(plan.complete()).isTrue();
    }

    private static String hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An exchange that records what a format audits; a query rides the path after {@code ?}. */
    private static final class Exchange implements build.jenesis.repository.format.FormatExchange {

        private final String method;
        private final String path;
        private final Map<String, String> query = new HashMap<>();
        private final byte[] body;
        private final Map<String, String> headers;
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private final List<String> audited = new ArrayList<>();
        private int status = -1;

        private Exchange(String method, String path, byte[] body, Map<String, String> headers) {
            this.method = method;
            int question = path.indexOf('?');
            this.path = question < 0 ? path : path.substring(0, question);
            if (question >= 0) {
                for (String pair : path.substring(question + 1).split("&")) {
                    int equals = pair.indexOf('=');
                    query.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            this.body = body;
            this.headers = headers;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return query.get(name);
        }

        @Override
        public String requestHeader(String name) {
            return headers.get(name);
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return response;
        }

        @Override
        public void audit(String action, String target) {
            audited.add(action + " " + target);
        }

        int status() {
            return status;
        }

        byte[] body() {
            return response.toByteArray();
        }

        List<String> audited() {
            return audited;
        }
    }
}
