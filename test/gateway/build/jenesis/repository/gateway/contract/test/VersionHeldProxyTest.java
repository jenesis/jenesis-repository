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
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
                AdvisorySource.none());
        return new ProxyScreen(gate, store, 0).wrap(upstream, path);
    }
}
