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
 * What the Terraform proxy leg serves in place of the upstream's documents: a provider's package document naming
 * this repository's own paths - with the upstream's sums, signature and signing keys reachable through them - and a
 * module's download naming this repository's archive only where the source is one it can hold.
 */
class TerraformProxyTest {

    private static final URI ROOT = URI.create("https://registry.invalid/");
    private static final String BASE = "/terraform/proxied";
    private static final String FILE = "terraform-provider-widget_1.0.0_linux_amd64.zip";
    private static final String PACKAGE = "v1/providers/acme/widget/1.0.0/download/linux/amd64";

    @TempDir
    Path root;

    @Test
    void a_package_document_names_this_repositorys_paths_and_keeps_the_upstreams_keys() throws IOException {
        ContractExchange exchange = proxy(BASE + "/" + PACKAGE, upstream(Map.of(ROOT + PACKAGE, packageDocument())));

        assertThat(exchange.status()).isEqualTo(200);
        String document = exchange.responseText();
        assertThat(document)
                .contains("/terraform/proxied/providers/acme/widget/1.0.0/" + FILE + "\"")
                .contains("/terraform/proxied/providers/acme/widget/1.0.0/SHA256SUMS?os=linux&arch=amd64\"")
                .contains("/terraform/proxied/providers/acme/widget/1.0.0/SHA256SUMS.sig?os=linux&arch=amd64\"")
                .contains("\"ascii_armor\":\"UPSTREAM KEY\"")
                .doesNotContain("releases.invalid");
    }

    @Test
    void the_sums_a_rewritten_document_names_are_the_upstreams() throws IOException {
        ContractExchange exchange = proxy(BASE + "/providers/acme/widget/1.0.0/SHA256SUMS",
                upstream(Map.of(ROOT + PACKAGE, packageDocument(),
                        "https://releases.invalid/widget/SHA256SUMS", "abc  " + FILE + "\n")),
                Map.of("os", "linux", "arch", "amd64"));

        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.responseText()).isEqualTo("abc  " + FILE + "\n");
    }

    @Test
    void a_module_whose_source_is_an_archive_downloads_through_this_repository() throws IOException {
        ContractExchange exchange = proxy(BASE + "/v1/modules/acme/network/aws/1.0.0/download",
                upstream(Map.of(), Map.of(ROOT + "v1/modules/acme/network/aws/1.0.0/download",
                        "https://releases.invalid/network-1.0.0.tar.gz")));

        assertThat(exchange.status()).isEqualTo(204);
        assertThat(exchange.responseHeader("X-Terraform-Get"))
                .endsWith("/terraform/proxied/modules/acme/network/aws/1.0.0.tar.gz");
    }

    @Test
    void a_module_whose_source_is_a_git_repository_is_relayed_as_it_is() throws IOException {
        String git = "git::https://github.invalid/acme/terraform-aws-network?ref=v1.0.0";
        ContractExchange exchange = proxy(BASE + "/v1/modules/acme/network/aws/1.0.0/download",
                upstream(Map.of(), Map.of(ROOT + "v1/modules/acme/network/aws/1.0.0/download", git)));

        assertThat(exchange.status()).isEqualTo(204);
        assertThat(exchange.responseHeader("X-Terraform-Get")).isEqualTo(git);
    }

    private static String packageDocument() {
        return "{\"protocols\":[\"5.0\"],\"os\":\"linux\",\"arch\":\"amd64\",\"filename\":\"" + FILE + "\","
                + "\"download_url\":\"https://releases.invalid/widget/" + FILE + "\","
                + "\"shasums_url\":\"https://releases.invalid/widget/SHA256SUMS\","
                + "\"shasums_signature_url\":\"https://releases.invalid/widget/SHA256SUMS.sig\","
                + "\"shasum\":\"" + "0".repeat(64) + "\","
                + "\"signing_keys\":{\"gpg_public_keys\":[{\"key_id\":\"ABCD\",\"ascii_armor\":\"UPSTREAM KEY\"}]}}";
    }

    private static ProxyFormat.Fetcher upstream(Map<String, String> documents) {
        return upstream(documents, Map.of());
    }

    /** An upstream answering {@code documents} by URL, and each of {@code downloads} as a module download naming its
     *  source in {@code X-Terraform-Get}. Anything else is a 404. */
    private static ProxyFormat.Fetcher upstream(Map<String, String> documents, Map<String, String> downloads) {
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (downloads.containsKey(url.toString())) {
                    return Optional.of(new ProxyFormat.Fetched(204, new byte[0],
                            Map.of("X-Terraform-Get", downloads.get(url.toString()))));
                }
                String document = documents.get(url.toString());
                return Optional.of(document == null ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                        : new ProxyFormat.Fetched(200, document.getBytes(StandardCharsets.UTF_8), Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return fetch(url, requestHeaders).map(fetched -> new ProxyFormat.Download(fetched.status(),
                        new ByteArrayInputStream(fetched.body()), fetched.headers()));
            }
        };
    }

    private ContractExchange proxy(String path, ProxyFormat.Fetcher fetcher) throws IOException {
        return proxy(path, fetcher, Map.of());
    }

    private ContractExchange proxy(String path, ProxyFormat.Fetcher fetcher, Map<String, String> query)
            throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            exchange = exchange.query(parameter.getKey(), parameter.getValue());
        }
        RepositoryFormat terraform = ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get).filter(format -> format.name().equals("terraform"))
                .findFirst().orElseThrow();
        ((ProxyFormat) terraform).proxy(exchange, store(), ROOT, fetcher);
        return exchange;
    }

    private ArtifactStore store() throws IOException {
        Path directory = Files.createDirectories(root.resolve("store"));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
