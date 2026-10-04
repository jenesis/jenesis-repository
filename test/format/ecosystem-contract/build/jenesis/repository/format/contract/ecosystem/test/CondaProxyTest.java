package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conda proxy leg holds a package to the SHA-256 its subdir's {@code repodata.json} records, and reads that index
 * beside the package: a screen over the fill judges the package, never the index, which it would otherwise hold in the
 * package's place.
 */
class CondaProxyTest {

    private static final URI ROOT = URI.create("https://conda.invalid/channel/");
    private static final String FILE = "widget-1.0-0.tar.bz2";

    @TempDir
    Path root;

    @Test
    void a_screen_judges_the_package_and_never_the_index_read_beside_it() throws IOException {
        byte[] archive = "a conda package".getBytes(StandardCharsets.UTF_8);
        byte[] repodata = ("{\"packages\":{\"" + FILE + "\":{\"name\":\"widget\",\"version\":\"1.0\",\"sha256\":\""
                + HexFormat.of().formatHex(sha256(archive)) + "\"}}}").getBytes(StandardCharsets.UTF_8);
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.of(
                url.equals(ROOT.resolve("noarch/repodata.json")) ? new ProxyFormat.Fetched(200, repodata, Map.of())
                        : url.equals(ROOT.resolve("noarch/" + FILE)) ? new ProxyFormat.Fetched(200, archive, Map.of())
                        : new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        JudgingScreen screen = new JudgingScreen(upstream);
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("conda");
        ContractExchange exchange = ContractExchange.of("GET", "/conda/channel/noarch/" + FILE);

        ((ProxyFormat) conda()).proxy(exchange, store, ROOT, screen);

        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.responseBytes()).isEqualTo(archive);
        assertThat(screen.judged).containsExactly(ROOT.resolve("noarch/" + FILE));
    }

    private static RepositoryFormat conda() {
        return RepositoryFormat.installed().stream().filter(format -> format.name().equals("conda")).findFirst()
                .orElseThrow();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
