package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.oci.inventory.OciBlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit guards for the capability-only {@link OciBlobLayout}: its {@code handles()}
 * must stay {@code false} forever (a future change to claim {@code /v2/} re-opens the first-match dispatch race with the
 * real, proxy-capable OCI format), and its {@code describe} must resolve ONLY a {@code /v2/<name>/manifests/<ref>} path,
 * to an {@code oci} descriptor - never a blob/catalog/tags path, and never a foreign ecosystem. Also pins the layout's
 * half of manifest validation: a root manifest that is present but unparseable/over-cap degrades {@code blobHashes} to
 * manifest-only AND WARNs (the layers are no longer enumerable, so the operator must {@code discard}); a degraded
 * SUB-manifest of an index stays silent, because a hostile index entry may legitimately point at a layer blob, which
 * has no children to lose. The root case is symmetric - a digest-rooted one WARNs too, which
 * {@code OciDerivationAgreementTest} pins beside the delegation that made it so.
 */
class OciBlobLayoutTest {

    private final OciBlobLayout layout = new OciBlobLayout();

    @TempDir
    Path root;

    @Test
    void handles_is_always_false_so_it_never_wins_format_dispatch() {
        for (String path : List.of("/v2", "/v2/", "/v2/library/app/manifests/latest",
                "/v2/library/app/blobs/sha256:" + "a".repeat(64), "/v2/_catalog", "/maven/x", "/npm/x")) {
            assertThat(layout.handles(path)).as("handles(%s) must be false", path).isFalse();
        }
    }

    @Test
    void serve_is_unreachable_and_throws() {
        // serve(), not handle(): handle() is the SPI's own entry point now - it screens the request path for
        // traversal and then delegates - so calling it with a null exchange asks the screen for a path first.
        // What this asserts is that the layout itself refuses to serve, which is the claim either way.
        assertThatThrownBy(() -> layout.serve(null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void describe_resolves_only_a_manifest_path_to_an_oci_descriptor() {
        assertThat(layout.describe("/v2/library/app/manifests/latest"))
                .hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.ecosystem()).isEqualTo("oci");
                    assertThat(descriptor.coordinate()).isEqualTo("library/app");
                    assertThat(descriptor.version()).isEqualTo("latest");
                });
        assertThat(layout.describe("/v2/library/app/manifests/sha256:" + "b".repeat(64)))
                .map(ArtifactDescriptor::version).hasValue("sha256:" + "b".repeat(64));
        // Non-manifest and malformed paths name no version.
        for (String path : List.of("/v2/library/app/blobs/sha256:" + "c".repeat(64), "/v2/_catalog",
                "/v2/library/app/tags/list", "/npm/left-pad/-/left-pad-1.0.0.tgz",
                "/v2/../app/manifests/latest", "/v2/library/app/manifests/bad tag")) {
            assertThat(layout.describe(path)).as("describe(%s) resolves no version", path).isEmpty();
        }
    }

    @Test
    void ecosystem_is_oci_and_name_is_distinct() {
        assertThat(layout.ecosystem()).isEqualTo("oci");
        assertThat(layout.name()).isEqualTo("oci-layout");
        assertThat(layout.blobRoots()).containsExactly("oci");
    }

    @Test
    void a_tag_rooted_unparseable_manifest_degrades_to_manifest_only_and_WARNs() throws IOException {
        ArtifactStore store = store();
        String name = "library/degraded";
        // A present-but-unparseable manifest blob (binary garbage, not JSON) referenced by a live tag pointer: the tag
        // contract says the blob is a manifest, so the failure to parse is the invariant break the WARN must surface.
        String hex = store.writeBlob(new ByteArrayInputStream(new byte[] {0, 1, 2, (byte) 0xff, 'x'}));
        store.writeVersioned("oci/" + name + "/tags/latest", ("sha256:" + hex).getBytes(StandardCharsets.UTF_8), null);

        ListAppender<ILoggingEvent> logs = captureLayoutLogs();
        assertThat(layout.blobHashes(name, "latest", store))
                .as("a tag-rooted unparseable manifest degrades to just the manifest hex").containsExactly(hex);
        assertThat(logs.list)
                .as("the degrade is WARNed, naming the coordinate:version so an operator can discard it")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(name + ":latest").contains("manifest-only");
                });
    }

    @Test
    void a_degraded_sub_manifest_of_an_index_stays_silent() throws IOException {
        ArtifactStore store = store();
        String name = "library/index";
        // A sub-manifest blob that is unparseable, referenced from an image index the tag resolves to. The ROOT (the
        // index) parses, so the layers-not-enumerable degrade is only on a sub-manifest - a hostile index entry may
        // legitimately point at a non-manifest, so this must NOT WARN.
        String subHex = store.writeBlob(new ByteArrayInputStream(new byte[] {(byte) 0x99, (byte) 0x88}));
        byte[] index = ("{\"manifests\":[{\"digest\":\"sha256:" + subHex + "\"}]}").getBytes(StandardCharsets.UTF_8);
        String indexHex = store.writeBlob(new ByteArrayInputStream(index));
        store.writeVersioned("oci/" + name + "/tags/latest", ("sha256:" + indexHex).getBytes(StandardCharsets.UTF_8),
                null);

        ListAppender<ILoggingEvent> logs = captureLayoutLogs();
        assertThat(layout.blobHashes(name, "latest", store))
                .as("the index root and its sub-manifest digest are both collected")
                .containsExactly(indexHex, subHex);
        assertThat(logs.list)
                .as("a degraded SUB-manifest is a silent degrade - no WARN")
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("manifest-only"));
    }

    @Test
    void an_image_is_made_of_its_blobs_then_its_platform_manifests_then_its_tag() throws IOException {
        ArtifactStore store = store();
        String name = "library/multi";
        String config = store.writeBlob(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        String layer = store.writeBlob(new ByteArrayInputStream("a layer".getBytes(StandardCharsets.UTF_8)));
        String platform = manifest(store, "{\"config\":{\"digest\":\"sha256:" + config + "\"},\"layers\":[{\"digest\":"
                + "\"sha256:" + layer + "\"}]}", "application/vnd.oci.image.manifest.v1+json");
        String index = manifest(store, "{\"manifests\":[{\"digest\":\"sha256:" + platform + "\"}]}",
                "application/vnd.oci.image.index.v1+json");
        store.writeVersioned("oci/" + name + "/tags/1.0", ("sha256:" + index).getBytes(StandardCharsets.UTF_8), null);

        assertThat(layout.contents(name, "1.0", store)).containsExactly(
                "/v2/" + name + "/blobs/sha256:" + config,
                "/v2/" + name + "/blobs/sha256:" + layer,
                "/v2/" + name + "/manifests/sha256:" + platform,
                "/v2/" + name + "/manifests/1.0");
        assertThat(layout.contents(name, "2.0", store)).as("a tag nothing is pushed to").isEmpty();
    }

    /** A manifest as the format accepts one: its blob, and the media type it records beside it. */
    private static String manifest(ArtifactStore store, String json, String mediaType) throws IOException {
        String hex = store.writeBlob(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        store.write("oci/.types/" + hex, new ByteArrayInputStream(mediaType.getBytes(StandardCharsets.UTF_8)));
        return hex;
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** Attach a logback list appender to the layout's logger so a test can assert (or assert the absence of) its WARN. */
    private static ListAppender<ILoggingEvent> captureLayoutLogs() {
        ch.qos.logback.classic.LoggerContext loggerContext =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger(OciBlobLayout.class.getName());
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
