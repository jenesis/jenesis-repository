package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * the blobs an OCI image keeps alive, and the garbage collection pass that would otherwise delete them.
 *
 * <p>The manifest is the only OCI blob a store key's <em>body</em> names (taught the mark phase to read that
 * body's {@code sha256:<hex>} dialect); an image's config and layer digests live inside the manifest document behind
 * no key at all, and a manifest pulled by digest has no tag pointer either. {@link OciFormat} lends that set through
 * {@link BlobReferences#references}, so this asserts both halves: the derivation itself - from a tag pointer, from the
 * per-manifest media-type sidecar, through an image index, and its refusal to answer short - and, end to end against a
 * real collector, that a pushed image still pulls after the two passes that used to reclaim it.
 */
class OciBlobReferencesTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciFormat format = new OciFormat();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** The collector the provider builds for a deployment with this format installed. */
    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock),
                Duration.ZERO, List.of(format));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Push a layer through the blob endpoint, exactly as {@code docker push} does. */
    private String pushBlob(String name, byte[] content) throws IOException {
        String hex = sha256(content);
        FakeExchange post = new FakeExchange("POST", "/v2/" + name + "/blobs/uploads/", content,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
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

    private static byte[] index(String... manifests) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (String manifest : manifests) {
            joiner.add("{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"size\":1,"
                    + "\"digest\":\"sha256:" + manifest + "\"}");
        }
        return ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.index.v1+json\","
                + "\"manifests\":" + joiner + "}").getBytes(StandardCharsets.UTF_8);
    }

    private String pushManifest(String name, String reference, byte[] body) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, body,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        return sha256(body);
    }

    @Test
    void a_tag_pointer_lends_the_manifest_its_config_and_its_layers() throws IOException {
        String config = pushBlob("library/app", "config".getBytes(StandardCharsets.UTF_8));
        String layer = pushBlob("library/app", "layer".getBytes(StandardCharsets.UTF_8));
        String manifest = pushManifest("library/app", "1.0", manifest(config, layer));

        assertThat(format.references("oci/library/app/tags/1.0", store))
                .as("the tag pointer keeps the whole image alive, not only the manifest its body names")
                .containsExactlyInAnyOrder(manifest, config, layer);
    }

    @Test
    void the_media_type_sidecar_lends_a_digest_only_images_whole_set() throws IOException {
        // The digest-only image: pushed by digest, never tagged - a legitimate OCI state this format serves straight
        // out of blobs/ and has no DELETE to retire. Its one durable record is the per-manifest media-type sidecar.
        String config = pushBlob("library/app", "untagged config".getBytes(StandardCharsets.UTF_8));
        String layer = pushBlob("library/app", "untagged layer".getBytes(StandardCharsets.UTF_8));
        byte[] body = manifest(config, layer);
        String manifest = pushManifest("library/app", "sha256:" + sha256(body), body);

        assertThat(store.exists("oci/types/" + manifest)).as("every accepted manifest gets its sidecar").isTrue();
        assertThat(store.list("oci/library/app/tags")).as("and a by-digest push links no tag").isEmpty();
        assertThat(format.references("oci/types/" + manifest, store))
                .containsExactlyInAnyOrder(manifest, config, layer);
    }

    @Test
    void an_image_index_lends_every_sub_manifest_and_its_layers() throws IOException {
        String configA = pushBlob("library/app", "amd64 config".getBytes(StandardCharsets.UTF_8));
        String layerA = pushBlob("library/app", "amd64 layer".getBytes(StandardCharsets.UTF_8));
        byte[] bodyA = manifest(configA, layerA);
        String manifestA = pushManifest("library/app", "sha256:" + sha256(bodyA), bodyA);
        String configB = pushBlob("library/app", "arm64 config".getBytes(StandardCharsets.UTF_8));
        String layerB = pushBlob("library/app", "arm64 layer".getBytes(StandardCharsets.UTF_8));
        byte[] bodyB = manifest(configB, layerB);
        String manifestB = pushManifest("library/app", "sha256:" + sha256(bodyB), bodyB);
        byte[] list = index(manifestA, manifestB);
        String indexHex = pushManifest("library/app", "multi", list);

        assertThat(format.references("oci/library/app/tags/multi", store))
                .as("an index is expanded through a work-list, each sub-manifest contributing its own config/layers")
                .containsExactlyInAnyOrder(indexHex, manifestA, configA, layerA, manifestB, configB, layerB);
    }

    @Test
    void a_key_that_names_no_image_lends_nothing() throws IOException {
        // The staging spaces of a push that never became an image, and a sidecar whose name is not a digest: empty is
        // the honest "keeps no further blob alive", and a never-finalized upload is what collection is FOR.
        store.writeVersioned("oci/uploads/session/0", "chunk".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("oci/upload-sessions/session", "1".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("oci/types/not-a-digest", "text/plain".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("oci/library/app/tags/dangling",
                "sha256:not-a-digest".getBytes(StandardCharsets.UTF_8), null);

        assertThat(format.references("oci/uploads/session/0", store)).isEmpty();
        assertThat(format.references("oci/upload-sessions/session", store)).isEmpty();
        assertThat(format.references("oci/types/not-a-digest", store)).isEmpty();
        assertThat(format.references("oci/library/app/tags/dangling", store)).isEmpty();
        assertThat(format.references("oci/library/app/tags/absent", store)).isEmpty();
        assertThat(format.references("publish/maven/some.jar", store))
                .as("a key outside this format's roots is never guessed at").isEmpty();
    }

    @Test
    void an_unreadable_manifest_refuses_rather_than_lending_a_short_set() throws IOException {
        // BlobReferences clause 3. A manifest blob that is present but unreadable cannot have its layers enumerated,
        // and answering "just the manifest" would hand a reference scan a short list - which is a live layer condemned
        // on one pass and deleted on the next. Ingest validation makes this unrepresentable going forward, so this is
        // the legacy-bytes path; it must fail loudly and name the key.
        byte[] junk = "not a manifest at all".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(junk);
        store.writeVersioned("blobs/" + hex, junk, null);
        store.writeVersioned("oci/types/" + hex,
                "application/vnd.oci.image.manifest.v1+json".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> format.references("oci/types/" + hex, store))
                // The named refusal of clause 3, not a bare IOException: these bytes will never parse, and a consumer
                // deriving the same set to decide what to WITHHOLD has to be able to degrade on exactly this without
                // also degrading on a store outage. It stays an IOException, so a collector that catches nothing at
                // all still fails its pass and deletes nothing.
                .isInstanceOf(BlobReferences.Unresolvable.class)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(hex)
                .hasMessageContaining("cannot be enumerated");
    }

    @Test
    void a_manifest_past_the_parse_bound_refuses_with_the_same_named_subtype() throws IOException {
        // The second refusal site: present, but larger than the manifest bound this format enforces on ingest, so its
        // layers cannot be enumerated either. Same fact as an unparseable manifest - the stored bytes refuse to be
        // read - so it must carry the same name, or a degrading consumer would have to catch one and propagate the
        // other for no reason it could state.
        String hex = "a".repeat(64);
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) '{');
        store.write("blobs/" + hex, new ByteArrayInputStream(oversized));
        store.writeVersioned("oci/types/" + hex,
                "application/vnd.oci.image.manifest.v1+json".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> format.references("oci/types/" + hex, store))
                .isInstanceOf(BlobReferences.Unresolvable.class)
                .hasMessageContaining(hex)
                .hasMessageContaining("manifest bound");
    }

    @Test
    void a_store_failure_stays_a_plain_io_exception_and_is_never_the_named_refusal() throws IOException {
        // The whole point of naming the refusal. If a store outage arrived as Unresolvable too, a consumer that
        // degrades on Unresolvable would read every store hiccup as "this image references nothing" - an
        // under-enforced hold, and on release a lifted marker for a layer another image still holds. The two refusal
        // sites are the ONLY ones that raise it; everything else is the store failing and says so.
        byte[] body = manifest(sha256("c".getBytes(StandardCharsets.UTF_8)));
        String hex = sha256(body);
        store.writeVersioned("blobs/" + hex, body, null);
        store.writeVersioned("oci/types/" + hex,
                "application/vnd.oci.image.manifest.v1+json".getBytes(StandardCharsets.UTF_8), null);
        ArtifactStore failing = new FailingReads(store);

        assertThat(format.references("oci/types/" + hex, store))
                .as("the same key resolves cleanly while the store answers").isNotEmpty();
        assertThatThrownBy(() -> format.references("oci/types/" + hex, failing))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(BlobReferences.Unresolvable.class);
    }

    /** A store whose blob reads fail the way a backend outage fails: {@code exists} still answers, the read does not.
     *  Everything else is the real store, so the only difference from the passing case is the failure itself. */
    private record FailingReads(ArtifactStore delegate) implements ArtifactStore {

        @Override
        public InputStream open(String key) throws IOException {
            throw new IOException("the store is unreachable: " + key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            throw new IOException("the store is unreachable: " + key);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new FailingReads(delegate.scope(tenant));
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    @Test
    void a_manifest_blob_that_is_already_gone_lends_only_itself() throws IOException {
        // Residue, not an invariant break: a sidecar whose manifest blob is absent keeps nothing else alive, and must
        // not be confused with the present-but-unreadable case above.
        String hex = sha256("gone".getBytes(StandardCharsets.UTF_8));
        store.writeVersioned("oci/types/" + hex,
                "application/vnd.oci.image.manifest.v1+json".getBytes(StandardCharsets.UTF_8), null);

        assertThat(format.references("oci/types/" + hex, store)).containsExactly(hex);
    }

    @Test
    void the_format_is_discovered_as_an_installed_reference_lender() {
        // Through the one `uses RepositoryFormat` clause every format already rides - never a second provides clause
        // or a second registry (design gate 3). This is the wiring the provider resolves; without it the derivations
        // above are dead code in a real deployment.
        assertThat(BlobReferences.installed())
                .anyMatch(OciFormat.class::isInstance);
        assertThat(BlobReferences.installed())
                .allSatisfy(lender -> assertThat(lender.blobRoots()).isNotEmpty());
    }

    @Test
    void a_pushed_image_survives_the_two_passes_that_used_to_reclaim_it() throws IOException {
        // The end-to-end claim, over the real format and the real collector: push an image, run the two
        // collection passes it takes to condemn and then delete, and pull every part of it back.
        String config = pushBlob("library/app", "e2e config".getBytes(StandardCharsets.UTF_8));
        String layer = pushBlob("library/app", "e2e layer".getBytes(StandardCharsets.UTF_8));
        String manifest = pushManifest("library/app", "1.0", manifest(config, layer));

        GcPlan first = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.complete()).isTrue();
        assertThat(first.condemned()).as("nothing a live image serves is even condemned").isZero();
        GcPlan second = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.complete()).isTrue();
        assertThat(second.collected()).isZero();

        FakeExchange pull = new FakeExchange("GET", "/v2/library/app/manifests/1.0");
        format.handle(pull, store);
        assertThat(pull.status()).isEqualTo(200);
        FakeExchange pullLayer = new FakeExchange("GET", "/v2/library/app/blobs/sha256:" + layer);
        format.handle(pullLayer, store);
        assertThat(pullLayer.status()).as("the layer still pulls - the 404 reported is closed").isEqualTo(200);
        FakeExchange pullConfig = new FakeExchange("GET", "/v2/library/app/blobs/sha256:" + config);
        format.handle(pullConfig, store);
        assertThat(pullConfig.status()).isEqualTo(200);
    }

    @Test
    void an_abandoned_upload_is_still_reclaimed() throws IOException {
        // The direction check on the other side: lending references must not turn garbage collection off for OCI. A
        // layer uploaded by a push that never sent its manifest is named by nothing, and is still condemned and
        // collected exactly as before.
        String orphan = pushBlob("library/app", "an abandoned layer".getBytes(StandardCharsets.UTF_8));

        GcPlan first = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.condemned()).isEqualTo(1);
        GcPlan second = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }
}
