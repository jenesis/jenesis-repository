package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gateway.ProxyScreen;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file fetched through a proxy for a version another file of which is held for review is held with it - a version is
 * reviewed whole, as the publish chain holds a file arriving for a held version - on the buffered, the streaming and
 * the bodiless leg alike, whatever the screen would have said of the file alone; a file of another version is judged
 * alone.
 */
class VersionHeldProxyTest {

    private static final String JAR = "/maven/org/acme/lib/1.0/lib-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        // The version's jar was held at its fill; nothing else of the version is cached yet.
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, JAR, publication.storeBlob(new ByteArrayInputStream(new byte[] {1})),
                "Maven", "org.acme:lib", "1.0");
    }

    @Test
    void a_file_of_a_held_version_is_held_with_it_on_the_buffered_leg() throws IOException {
        String pom = "/maven/org/acme/lib/1.0/lib-1.0.pom";

        assertThat(screen(pom).fetch(URI.create("http://up" + pom), Map.of())).as("withheld").isEmpty();

        assertThat(new Publication(store).located("/quarantine" + pom)).as("kept for review with its version")
                .isPresent();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.rules()).contains(ComplianceGate.VERSION_HELD_RULE));
    }

    @Test
    void a_file_of_a_held_version_is_held_with_it_on_the_streaming_and_bodiless_legs() throws IOException {
        String pom = "/maven/org/acme/lib/1.0/lib-1.0.pom";

        assertThat(screen(pom).head(URI.create("http://up" + pom), Map.of())).as("no size disclosed").isEmpty();
        assertThat(screen(pom).download(URI.create("http://up" + pom), Map.of())).as("not streamed").isEmpty();
        assertThat(new Publication(store).located("/quarantine" + pom)).isPresent();
    }

    @Test
    void a_distribution_s_metadata_sidecar_is_a_file_of_its_version() throws IOException {
        Publication publication = new Publication(store);
        String wheel = "/pypi/simple/widget/widget-1.0.0-py3-none-any.whl";
        HeldSubjects.hold(publication, store, wheel, publication.storeBlob(new ByteArrayInputStream(new byte[] {2})),
                "PyPI", "widget", "1.0.0");
        String metadata = wheel + ".metadata";
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (_, _) -> Optional.of(new ProxyFormat.Fetched(
                200, "Metadata-Version: 2.1\nName: widget\nVersion: 1.0.0\n".getBytes(StandardCharsets.UTF_8),
                Map.of()));

        assertThat(new ProxyScreen(gate(), store, 0).wrap(upstream, metadata)
                .fetch(URI.create("http://up" + metadata), Map.of())).as("held with the wheel it describes").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.rules()).contains(ComplianceGate.VERSION_HELD_RULE));
    }

    @Test
    void a_gem_s_quick_spec_is_a_file_of_its_version() throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, "/rubygems/gems/widget-1.2.3.gem",
                publication.storeBlob(new ByteArrayInputStream(new byte[] {3})), "RubyGems", "widget", "1.2.3");
        String quick = "/rubygems/quick/Marshal.4.8/widget-1.2.3.gemspec.rz";
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (_, _) -> Optional.of(new ProxyFormat.Fetched(
                200, new byte[] {0x78, 0x01}, Map.of()));

        assertThat(new ProxyScreen(gate(), store, 0).wrap(upstream, quick)
                .fetch(URI.create("http://up" + quick), Map.of())).as("held with the gem it specifies").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.rules()).contains(ComplianceGate.VERSION_HELD_RULE));
    }

    @Test
    void a_debian_source_package_s_files_are_files_of_its_version() throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, "/debian/pool/main/w/widget/widget_1.0-1.dsc",
                publication.storeBlob(new ByteArrayInputStream(new byte[] {4})), "Debian", "widget", "1.0-1");
        String tarball = "/debian/pool/main/w/widget/widget_1.0-1.debian.tar.xz";
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (_, _) -> Optional.of(new ProxyFormat.Fetched(
                200, new byte[] {(byte) 0xfd, '7', 'z', 'X', 'Z', 0}, Map.of()));

        assertThat(new ProxyScreen(gate(), store, 0).wrap(upstream, tarball)
                .fetch(URI.create("http://up" + tarball), Map.of())).as("held with its source package").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.rules()).contains(ComplianceGate.VERSION_HELD_RULE));
    }

    @Test
    void a_go_module_s_info_and_mod_are_files_of_its_version() throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, "/go/example.com/widget/@v/v1.0.0.zip",
                publication.storeBlob(new ByteArrayInputStream(new byte[] {5})), "Go", "example.com/widget", "v1.0.0");
        String info = "/go/example.com/widget/@v/v1.0.0.info";
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (_, _) -> Optional.of(new ProxyFormat.Fetched(
                200, "{\"Version\":\"v1.0.0\",\"Time\":\"2020-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8),
                Map.of()));

        assertThat(new ProxyScreen(gate(), store, 0).wrap(upstream, info)
                .fetch(URI.create("http://up" + info), Map.of())).as("withheld").isEmpty();
        assertThat(new Publication(store).located("/quarantine" + info))
                .as("held for review with its version, rather than refused as a document naming none").isPresent();
    }

    @Test
    void a_file_of_another_version_is_judged_alone() throws IOException {
        String pom = "/maven/org/acme/lib/1.1/lib-1.1.pom";

        assertThat(screen(pom).fetch(URI.create("http://up" + pom), Map.of())).isPresent();
        assertThat(new QuarantineLog(store).events()).isEmpty();
    }

    /** The screen over an upstream answering every path with a POM of the version the path names, and the headers a
     *  HEAD answers with, behind a gate no advisory holds anything for. */
    private ProxyFormat.Fetcher screen(String path) {
        String version = path.split("/")[5];
        byte[] body = ("<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>lib"
                + "</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8);
        ProxyFormat.Fetcher upstream = new ProxyFormat.Fetcher.Buffered() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Fetched(200, body, Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Head(200, Map.of("content-length", String.valueOf(body.length))));
            }
        };
        return new ProxyScreen(gate(), store, 0).wrap(upstream, path);
    }

    private static ComplianceGate gate() {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), AdvisorySource.none());
    }
}
