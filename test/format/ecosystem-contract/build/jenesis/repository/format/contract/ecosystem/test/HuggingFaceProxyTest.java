package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Hugging Face proxy leg holds an LFS file to the sha256 the Hub's revision document lists for it. The Hub answers
 * such a file with a redirect to its CDN, and the CDN answers with an {@code ETag} of its own - a hash of its own
 * storage, which is no digest of the bytes - so the file is held to the list rather than to anything on the answer.
 */
class HuggingFaceProxyTest {

    private static final URI ROOT = URI.create("https://hub.invalid/");
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String FILE = "model.bin";
    private static final String REQUEST = "/huggingface/hub/acme/model/resolve/" + COMMIT + "/" + FILE;

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }

    @Test
    void a_file_a_cdn_serves_under_an_etag_of_its_own_is_held_to_the_listed_digest() throws IOException {
        byte[] weights = "the weights".getBytes(StandardCharsets.UTF_8);
        ContractExchange exchange = proxy(store("listed"), upstream(weights, sha256(weights)));

        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.responseBytes()).isEqualTo(weights);
    }

    @Test
    void a_file_whose_bytes_differ_from_the_listed_digest_is_refused() throws IOException {
        byte[] weights = "the weights".getBytes(StandardCharsets.UTF_8);
        ArtifactStore store = store("tampered");
        ContractExchange exchange = proxy(store, upstream(weights,
                sha256("other weights".getBytes(StandardCharsets.UTF_8))));

        assertThat(exchange.status()).isNotEqualTo(200);
        ContractExchange again = ContractExchange.of("GET", REQUEST);
        huggingface().handle(again, store);
        assertThat(again.status()).as("nothing was cached").isEqualTo(404);
    }

    /** A Hub whose revision document lists {@link #FILE} as an LFS file with {@code listed} as its sha256, and whose
     *  resolve answers {@code weights} as a CDN does: under an {@code ETag} that is a 64-hex hash of something else. */
    private static ProxyFormat.Fetcher upstream(byte[] weights, String listed) {
        String revision = ROOT + "api/models/acme/model/revision/" + COMMIT + "?blobs=true";
        String document = "{\"sha\":\"" + COMMIT + "\",\"siblings\":[{\"rfilename\":\"" + FILE + "\",\"size\":"
                + weights.length + ",\"lfs\":{\"sha256\":\"" + listed + "\",\"size\":" + weights.length + "}}]}";
        return (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.of(
                url.toString().equals(revision)
                        ? new ProxyFormat.Fetched(200, document.getBytes(StandardCharsets.UTF_8), Map.of())
                        : url.equals(ROOT.resolve("acme/model/resolve/" + COMMIT + "/" + FILE))
                                ? new ProxyFormat.Fetched(200, weights, Map.of("X-Repo-Commit", COMMIT,
                                        "ETag", "\"" + "e".repeat(64) + "\""))
                                : new ProxyFormat.Fetched(404, new byte[0], Map.of()));
    }

    private static ContractExchange proxy(ArtifactStore store, ProxyFormat.Fetcher upstream) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", REQUEST);
        ((ProxyFormat) huggingface()).proxy(exchange, store, ROOT, upstream);
        return exchange;
    }

    private static RepositoryFormat huggingface() {
        return RepositoryFormat.installed().stream().filter(format -> format.name().equals("huggingface"))
                .findFirst().orElseThrow();
    }

    private ArtifactStore store(String name) {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope(name);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
