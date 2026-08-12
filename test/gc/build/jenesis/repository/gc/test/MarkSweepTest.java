package build.jenesis.repository.gc.test;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.StoreInvariants;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mark-sweep collector's data-safety core over a real filesystem store: an orphan is condemned by one pass
 * and collected only by a later one - never by the pass that first judged it - while a referenced, re-linked or
 * unrecognisable blob is never deleted, and the {@code gc/} bookkeeping converges to nothing on a clean store.
 */
class MarkSweepTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    /** A collector handed the reference-lending formats a deployment installs - what the provider builds. */
    private MarkSweepGarbageCollector collector(BlobReferences... lenders) {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock),
                Duration.ZERO, List.of(lenders));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void an_orphan_is_condemned_then_collected_and_a_referenced_blob_never_is() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        GcPlan first = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(first.complete()).isTrue();
        assertThat(first.condemned()).isEqualTo(1);
        assertThat(first.collected()).isZero();
        assertThat(store.exists("blobs/" + orphan)).as("the first judgment condemns, never deletes").isTrue();
        assertThat(store.exists("gc/condemned/" + orphan)).isTrue();

        GcPlan second = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(second.complete()).isTrue();
        assertThat(second.collected()).isEqualTo(1);
        assertThat(second.sample()).containsExactly(orphan);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
        assertThat(store.exists("gc/condemned/" + orphan)).as("a collected blob leaves no marker").isFalse();
        assertThat(store.exists("blobs/" + kept)).isTrue();
        new StoreInvariants(store).assertConsistent();
    }

    @Test
    void a_blob_written_between_passes_gets_a_full_interval_of_grace() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());

        String late = publication.storeBlob(bytes("in flight"));
        GcPlan second = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(second.condemned()).isEqualTo(1);
        assertThat(store.exists("blobs/" + late))
                .as("a blob younger than one pass is condemned at most, never deleted").isTrue();

        GcPlan third = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(third.collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + late)).isFalse();
    }

    @Test
    void a_condemned_blob_relinked_through_publication_is_never_collected() throws IOException {
        // The dedup re-publish race: identical content dedupes to the blob a pass already condemned; the re-link
        // clears the marker on the write path, so the next sweep has nothing due.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("deduped"));
        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(store.exists("gc/condemned/" + blob)).isTrue();

        publication.link("/maven/back.jar", blob);
        assertThat(store.exists("gc/condemned/" + blob)).as("the link un-condemned the blob").isFalse();

        GcPlan next = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(next.collected()).isZero();
        assertThat(store.exists("blobs/" + blob)).isTrue();
    }

    @Test
    void a_pointer_written_outside_publication_is_spared_by_the_next_mark() throws IOException {
        // A blobs-namespace format links its pointers under its own roots, without Publication.link's marker
        // clear: the next pass's mark sees the pointer, spares the blob and removes the stale marker itself.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("npm tarball"));
        var _ = collector().collect(store, Known.known(List.of("publish", "npm")), clock.instant());
        assertThat(store.exists("gc/condemned/" + blob)).isTrue();

        store.writeVersioned("npm/lodash/4.0.0/pointer", blob.getBytes(StandardCharsets.UTF_8), null);
        GcPlan next = collector().collect(store, Known.known(List.of("publish", "npm")), clock.instant());
        assertThat(next.collected()).isZero();
        assertThat(next.spared()).isEqualTo(1);
        assertThat(store.exists("blobs/" + blob)).isTrue();
        assertThat(store.exists("gc/condemned/" + blob)).as("the stale marker converged away").isFalse();
    }

    @Test
    void a_qualified_pointer_body_puts_its_blob_in_the_reference_set() throws IOException {
        // The OCI Distribution dialect: oci/<name>/tags/<tag> holds "sha256:<hex>", not the bare hex the publish/
        // pointers carry (exactly what OciFormat.linkTag writes), and `oci` is in the pointer-root union the
        // downstream callers hand collect(). Read as bare hex the body parses as nothing, the blob it references
        // never enters the reference set, and the sweep condemns and then DELETES a live image - so this is the
        // deletion the mark's normalisation exists to prevent, not a cosmetic parse.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("an oci manifest"));
        store.writeVersioned("oci/library/app/tags/1.0",
                ("sha256:" + blob).getBytes(StandardCharsets.UTF_8), null);

        GcPlan first = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.complete()).isTrue();
        assertThat(first.condemned()).as("a referenced blob is never even condemned").isZero();
        assertThat(store.exists("gc/condemned/" + blob)).isFalse();
        // The reference is recorded under the BARE hash's leading-byte shard - the only place the sweep, which names a
        // blob by its bare hex, ever looks. A predicate merely widened to accept the qualifier would shard it under
        // "sh" and record "sha256:<hex>", which no sweep would ever match; normalising at the body read is what puts
        // the hash where it is looked for.
        assertThat(store.list("gc/1/refs/" + blob.substring(0, 2)))
                .as("the qualified body's hash lands in the bare-hex shard the sweep reads").isNotEmpty();

        GcPlan second = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.complete()).isTrue();
        assertThat(second.collected()).isZero();
        assertThat(store.exists("blobs/" + blob))
                .as("the blob an OCI tag pointer references survives every sweep").isTrue();
    }

    @Test
    void a_qualified_pointer_body_spares_an_already_condemned_blob() throws IOException {
        // The §13 twin of the bare-hex leg above: a blobs-namespace format links its pointer after a pass condemned
        // the blob, and the next mark must spare it and converge the stale marker away whichever dialect the body
        // spells the hash in.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("a re-tagged manifest"));
        var _ = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(store.exists("gc/condemned/" + blob)).isTrue();

        store.writeVersioned("oci/library/app/tags/latest",
                ("sha256:" + blob).getBytes(StandardCharsets.UTF_8), null);
        GcPlan next = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(next.collected()).isZero();
        assertThat(next.spared()).isEqualTo(1);
        assertThat(store.exists("blobs/" + blob)).isTrue();
        assertThat(store.exists("gc/condemned/" + blob)).as("the stale marker converged away").isFalse();
    }

    @Test
    void a_tagged_images_config_and_layers_are_deleted_when_its_format_lends_nothing() throws IOException {
        // The D-027 reproduction, and the negative control for every leg below. D-022 made the MANIFEST reachable (its
        // sha256:<hex> tag-pointer body now names it), but the manifest is the ONLY OCI blob any pointer body names:
        // an image's config and layer digests live INSIDE the manifest document, behind no store key at all. With no
        // lender the mark counts them not at all, so one pass condemns them and the next DELETES them - leaving a
        // manifest that pulls 200 and layers that 404, which is what this asserts happens.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String config = publication.storeBlob(bytes("an image config"));
        String layer = publication.storeBlob(bytes("a layer tarball"));
        String manifest = publication.storeBlob(bytes(manifest(config, layer)));
        store.writeVersioned("oci/library/app/tags/1.0",
                ("sha256:" + manifest).getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("oci/types/" + manifest, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        GcPlan first = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.condemned()).as("the config and the layer are condemned; only the manifest is named").isEqualTo(2);

        GcPlan second = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.collected()).isEqualTo(2);
        assertThat(store.exists("blobs/" + manifest)).as("the manifest survives (D-022)").isTrue();
        assertThat(store.exists("blobs/" + config)).as("D-027: the live image's config is DELETED").isFalse();
        assertThat(store.exists("blobs/" + layer)).as("D-027: the live image's layer is DELETED").isFalse();
    }

    @Test
    void a_tagged_images_config_and_layers_are_never_collected() throws IOException {
        // The same store, with the format lending what its documents keep alive: the mark unions the lent hashes into
        // the same reference shards the sweep reads, so nothing an image serves is ever even condemned. The collector
        // still parses nothing - the derivation is the format's.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String config = publication.storeBlob(bytes("an image config"));
        String layer = publication.storeBlob(bytes("a layer tarball"));
        String manifest = publication.storeBlob(bytes(manifest(config, layer)));
        store.writeVersioned("oci/library/app/tags/1.0",
                ("sha256:" + manifest).getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("oci/types/" + manifest, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        GcPlan first = collector(new DocumentReferences())
                .collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.complete()).isTrue();
        assertThat(first.condemned()).as("every blob a live image serves is referenced, so none is condemned").isZero();
        // The lent hashes land in the BARE-hex leading-byte shards the sweep reads, exactly as a pointer body's does.
        assertThat(store.list("gc/1/refs/" + layer.substring(0, 2))).isNotEmpty();

        GcPlan second = collector(new DocumentReferences())
                .collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.complete()).isTrue();
        assertThat(second.collected()).isZero();
        assertThat(store.exists("blobs/" + manifest)).as("the manifest survives (D-022)").isTrue();
        assertThat(store.exists("blobs/" + config)).as("the config the manifest names survives").isTrue();
        assertThat(store.exists("blobs/" + layer)).as("the layer the manifest names survives").isTrue();
        // Deliberately no StoreInvariants sweep here: its unreferenced-blob leg knows only publish/ pointers, and an
        // image's config and layer are referenced through the format's own document - the very thing under test.
    }

    @Test
    void a_digest_only_manifest_and_its_layers_are_never_collected() throws IOException {
        // The other half of D-027: a manifest pulled by digest and never tagged is a legitimate OCI state (the format
        // serves /v2/<name>/manifests/sha256:<hex> straight out of blobs/, and its API has no DELETE to retire one),
        // and it carries no tag pointer - so even after D-022 NOTHING named it and the sweep deleted the whole image,
        // manifest included. The per-document sidecar oci/types/<hex> is the durable record that this hex is a manifest
        // the registry ingested and serves; resolving the image from that key is what makes the untagged case reachable
        // at all, and it is why a lender is asked about every key under its roots, not only pointer-shaped ones.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String config = publication.storeBlob(bytes("an untagged image config"));
        String layer = publication.storeBlob(bytes("an untagged layer tarball"));
        String manifest = publication.storeBlob(bytes(manifest(config, layer)));
        store.writeVersioned("oci/types/" + manifest, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        GcPlan bare = collector().collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(bare.condemned())
                .as("the negative control: with no lender the whole untagged image is condemned").isEqualTo(3);

        ArtifactStore lending = store();
        Publication other = new Publication(lending);
        String config2 = other.storeBlob(bytes("an untagged image config"));
        String layer2 = other.storeBlob(bytes("an untagged layer tarball"));
        String manifest2 = other.storeBlob(bytes(manifest(config2, layer2)));
        lending.writeVersioned("oci/types/" + manifest2, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        GcPlan first = collector(new DocumentReferences())
                .collect(lending, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(first.condemned()).as("a digest-only image is live content, so nothing is condemned").isZero();

        GcPlan second = collector(new DocumentReferences())
                .collect(lending, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(second.collected()).isZero();
        assertThat(lending.exists("blobs/" + manifest2)).as("the untagged manifest survives").isTrue();
        assertThat(lending.exists("blobs/" + config2)).isTrue();
        assertThat(lending.exists("blobs/" + layer2)).isTrue();
    }

    @Test
    void a_lender_that_cannot_enumerate_a_key_fails_the_pass_rather_than_under_reporting() throws IOException {
        // BlobReferences clause 3, and the one clause that keeps this seam from being a new way to lose data: a lender
        // that recognises a key but cannot resolve what it keeps alive must throw, never answer a short list, because
        // the mark cannot tell a short list from a complete one. The pass fails and the sweep is never reached, so
        // nothing is condemned and nothing is deleted - "I do not know" resolves to keeping everything.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String layer = publication.storeBlob(bytes("a layer of an unreadable image"));
        String manifest = publication.storeBlob(bytes("!! not a document this format can read " + layer));
        store.writeVersioned("oci/types/" + manifest, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        MarkSweepGarbageCollector collector = collector(new DocumentReferences());
        assertThatThrownBy(() -> collector.collect(store, Known.known(List.of("publish", "oci")), clock.instant()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be enumerated");
        assertThat(store.exists("gc/condemned/" + layer))
                .as("a failed mark never judges a blob, let alone condemns one").isFalse();
        assertThat(store.exists("blobs/" + layer)).isTrue();
        assertThat(store.exists("blobs/" + manifest)).isTrue();
    }

    @Test
    void a_lender_is_only_asked_about_keys_beneath_its_own_roots() throws IOException {
        // A lender is narrowed to the roots it declared, so a pointer-only deployment pays one prefix test per leaf and
        // a format is never handed a neighbour's key to guess at. The publish/ leaf below is a key no lender owns.
        ArtifactStore store = store();
        List<String> asked = new ArrayList<>();
        BlobReferences recording = new BlobReferences() {
            @Override
            public List<String> blobRoots() {
                return List.of("oci");
            }

            @Override
            public List<String> references(String key, ArtifactStore ignored) {
                asked.add(key);
                return List.of();
            }
        };
        Publication publication = new Publication(store);
        publication.link("/maven/kept.jar", publication.storeBlob(bytes("a maven artifact")));
        store.writeVersioned("oci/library/app/tags/1.0", ("sha256:" + "0".repeat(64))
                .getBytes(StandardCharsets.UTF_8), null);

        var _ = collector(recording).collect(store, Known.known(List.of("publish", "oci")), clock.instant());
        assertThat(asked).containsExactly("oci/library/app/tags/1.0");
    }

    @Test
    void a_lenders_own_roots_are_marked_even_when_the_caller_forgot_them() throws IOException {
        // The caller owns the layout, but a lender declaring "my blobs live under oci/" is the format's own word for
        // it - and a caller that omits that root has the lender installed and never asks it, which is exactly how a
        // blobs-namespace format's content becomes invisible to the scan. The union can only enumerate MORE.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String config = publication.storeBlob(bytes("a config under a forgotten root"));
        String layer = publication.storeBlob(bytes("a layer under a forgotten root"));
        String manifest = publication.storeBlob(bytes(manifest(config, layer)));
        store.writeVersioned("oci/types/" + manifest, OCI_MANIFEST_TYPE.getBytes(StandardCharsets.UTF_8), null);

        GcPlan only = collector(new DocumentReferences()).collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(only.condemned()).as("the lender's own root is walked though the caller named only publish").isZero();
        assertThat(store.exists("blobs/" + layer)).isTrue();
    }

    @Test
    void a_lender_may_not_claim_a_namespace_the_collector_itself_judges() throws IOException {
        // The roots screen the caller's pointer roots already pass: a format declaring blobs/, gc/ or walks/ as its own
        // is a wiring bug, and accepting it would let a lender speak for the namespace being swept.
        BlobReferences claiming = new BlobReferences() {
            @Override
            public List<String> blobRoots() {
                return List.of("blobs");
            }
        };
        assertThatThrownBy(() -> collector(claiming))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a pointer root: blobs");
    }

    private static final String OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";

    /** A minimal but real OCI image manifest - the shape whose config/layer digests live behind no store key. */
    private static String manifest(String config, String layer) {
        return "{\"schemaVersion\":2,\"mediaType\":\"" + OCI_MANIFEST_TYPE + "\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":15,"
                + "\"digest\":\"sha256:" + config + "\"},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":15,"
                + "\"digest\":\"sha256:" + layer + "\"}]}";
    }

    @Test
    void only_recognised_content_addressed_objects_are_ever_judged() throws IOException {
        ArtifactStore store = store();
        store.writeVersioned("blobs/not-a-content-hash", "junk".getBytes(StandardCharsets.UTF_8), null);
        byte[] large = new byte[2048]; // an oversized "pointer" leaf is other metadata, skipped unread
        store.writeVersioned("publish/maven/metadata.xml", large, null);

        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(store.exists("blobs/not-a-content-hash"))
                .as("a name that is no SHA-256 is never judged, let alone deleted").isTrue();
        assertThat(store.list("gc/condemned")).isEmpty();
        assertThat(store.exists("publish/maven/metadata.xml")).isTrue();
    }

    @Test
    void bookkeeping_converges_and_superseded_reference_shards_are_dropped() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));
        String stale = "ab".repeat(32); // a marker whose blob is long gone - crash residue
        store.writeVersioned("gc/condemned/" + stale,
                "pass=1\nsince=2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);

        var _ = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        GcPlan second = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(second.collected()).isEqualTo(1);
        assertThat(store.exists("gc/condemned/" + stale)).as("a blob-less marker is swept").isFalse();
        assertThat(store.list("gc/1")).as("pass 1's reference shards were superseded and dropped").isEmpty();

        GcPlan converged = collector().collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(converged.complete()).isTrue();
        assertThat(converged.isEmpty()).as("a re-run over a converged store changes nothing").isTrue();
        assertThat(store.exists("blobs/" + kept)).isTrue();
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }

    @Test
    void a_sweep_never_deletes_after_its_reference_shards_are_superseded() throws IOException {
        // The lease fence: a paused or lease-expired sweep worker that resumes after a newer mark generation has
        // superseded (and whose converge may have dropped) its reference shards must not judge a blob against the
        // emptied shards and delete a still-referenced one. Standing in for that, a decorator makes the mark manifest
        // report a newer generation during the sweep - exactly what a concurrent node's later mark presents - and the
        // sweep must refuse the delete, deferring it rather than deleting against a superseded reference set.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // Pass 1 condemns the orphan; a normal pass 2 would collect it.
        assertThat(collector().collect(store, Known.known(List.of("publish")), clock.instant()).condemned()).isEqualTo(1);

        ArtifactWalk inflating = new GenerationInflatingMarkWalk(
                new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
        GcPlan fenced = new MarkSweepGarbageCollector(inflating).collect(store, Known.known(List.of("publish")), clock.instant());
        assertThat(fenced.collected()).as("a superseded reference set fences the delete").isZero();
        assertThat(store.exists("blobs/" + orphan))
                .as("the orphan is deferred, never deleted against dropped shards").isTrue();

        // With the shards standing again (a normal walk) the deferred orphan is reclaimed - the fence only ever
        // delays a delete, never loses one - and the referenced blob is untouched throughout.
        assertThat(collector().collect(store, Known.known(List.of("publish")), clock.instant()).collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
        assertThat(store.exists("blobs/" + kept)).isTrue();
    }

    /** Forwards to a real walk but, once the sweep phase begins, reports the mark pass one generation newer than it
     *  truly is - standing in for a concurrent node whose later mark has superseded (and whose converge may have
     *  dropped) this sweep's reference shards. The mark phase itself runs against the true generation, so the shards
     *  are written where the sweep loads them; only the fence's re-read of the mark manifest sees the advance. */
    private static final class GenerationInflatingMarkWalk implements ArtifactWalk {

        private final ArtifactWalk delegate;
        private volatile boolean sweeping;

        private GenerationInflatingMarkWalk(ArtifactWalk delegate) {
            this.delegate = delegate;
        }

        @Override
        public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
                throws IOException {
            if (consumer.equals("gc-sweep")) {
                sweeping = true;
            }
            return delegate.walk(store, consumer, roots, visitor);
        }

        @Override
        public Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException {
            Optional<WalkPass> real = delegate.pass(store, consumer);
            if (sweeping && consumer.equals("gc-mark") && real.isPresent()) {
                WalkPass pass = real.get();
                return Optional.of(new WalkPass(pass.generation() + 1, pass.started(), pass.roots(),
                        pass.segments(), pass.done(), pass.status()));
            }
            return real;
        }

        @Override
        public List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException {
            return delegate.segments(store, consumer);
        }
    }

    @Test
    void empty_and_reserved_pointer_roots_are_refused() {
        ArtifactStore store = store();
        assertThatThrownBy(() -> collector().collect(store, Known.known(List.of()), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String reserved : List.of("blobs", "gc", "walks")) {
            assertThatThrownBy(() -> collector().collect(store, Known.known(List.of("publish", reserved)), clock.instant()))
                    .as("marking %s as a pointer root is a caller bug", reserved)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> collector().plan(store, Known.known(List.of(reserved)), clock.instant()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
