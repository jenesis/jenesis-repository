package build.jenesis.repository.importer.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportDownloads;
import build.jenesis.repository.importer.ImportFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A connector's download carries the incumbent's credential to the incumbent alone, and fails on anything but a
 *  {@code 200} by the status's kind. */
class ImportDownloadsTest {

    private static final URI SOURCE = URI.create("https://nexus.example/repository/releases");
    private static final Map<String, String> CREDENTIAL = Map.of("Authorization", "Basic c2VjcmV0");

    private final Map<URI, Map<String, String>> sent = new LinkedHashMap<>();

    private ProxyFormat.Fetcher answering(int status) {
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                throw new AssertionError("an asset is downloaded, never buffered");
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                sent.put(url, requestHeaders);
                return Optional.of(new ProxyFormat.Download(status,
                        new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) {
                throw new AssertionError("an asset is downloaded, never probed");
            }

            @Override
            public ProxyFormat.Fetcher beside() {
                return this;
            }
        };
    }

    @Test
    void the_credential_goes_to_the_incumbents_origin_and_nowhere_else() throws IOException {
        URI own = URI.create("https://nexus.example:443/repository/releases/a/1/a-1.jar");
        URI elsewhere = URI.create("https://cdn.example/a/1/a-1.jar");
        ProxyFormat.Fetcher fetcher = answering(200);

        try (InputStream in = ImportDownloads.open(fetcher, SOURCE, own, CREDENTIAL)) {
            assertThat(in.readAllBytes()).isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
        }
        ImportDownloads.open(fetcher, SOURCE, elsewhere, CREDENTIAL).close();

        assertThat(sent.get(own)).isEqualTo(CREDENTIAL);
        assertThat(sent.get(elsewhere)).as("a listing naming another origin is downloaded anonymously").isEmpty();
    }

    @Test
    void anything_but_a_200_fails_by_its_kind() {
        assertThatThrownBy(() -> ImportDownloads.open(answering(401), SOURCE, SOURCE, CREDENTIAL))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.AUTH));
        assertThatThrownBy(() -> ImportDownloads.open(answering(503), SOURCE, SOURCE, CREDENTIAL))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.TRANSIENT));
    }
}
