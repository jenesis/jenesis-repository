package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

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
    private static final String DOWNLOAD = ROOT + "v1/modules/acme/network/aws/1.0.0/download";
    private static final UnaryOperator<String> GITHUB =
            key -> key.equals("terraform.git-hosts") ? "github.invalid=github" : null;

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

    @Test
    void a_git_source_on_a_listed_host_downloads_as_its_refs_archive() throws IOException {
        String git = "git::https://github.invalid/acme/terraform-aws-network.git//modules/vpc?ref=v1.0.0";
        byte[] archive = tarGz("terraform-aws-network-1.0.0/modules/vpc/main.tf");
        List<String> asked = new ArrayList<>();
        ProxyFormat.Fetcher upstream = recording(asked, upstream(Map.of(), Map.of(DOWNLOAD, git),
                Map.of("https://github.invalid/acme/terraform-aws-network/archive/v1.0.0.tar.gz", archive)));

        ContractExchange download = proxy(BASE + "/v1/modules/acme/network/aws/1.0.0/download", upstream, GITHUB);
        assertThat(download.status()).isEqualTo(204);
        assertThat(download.responseHeader("X-Terraform-Get"))
                .as("the archive holds one directory the host names, and the source's own subdirectory inside it")
                .endsWith("/terraform/proxied/modules/acme/network/aws/1.0.0.tar.gz"
                        + "//terraform-aws-network-1.0.0/modules/vpc");

        ContractExchange fetched = proxy(BASE + "/modules/acme/network/aws/1.0.0.tar.gz", upstream, GITHUB);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.responseBytes()).isEqualTo(archive);
        assertThat(asked).contains("https://github.invalid/acme/terraform-aws-network/archive/v1.0.0.tar.gz");
    }

    @Test
    void each_kind_of_git_host_is_asked_for_its_own_archive_path() throws IOException {
        Map<String, String> sources = Map.of(
                "git::https://gitlab.invalid/acme/infra/network.git?ref=v2.1.0",
                "https://gitlab.invalid/acme/infra/network/-/archive/v2.1.0/network-v2.1.0.tar.gz",
                "git::https://bitbucket.invalid/acme/network.git?ref=0123abcd",
                "https://bitbucket.invalid/acme/network/get/0123abcd.tar.gz");
        for (Map.Entry<String, String> source : sources.entrySet()) {
            List<String> asked = new ArrayList<>();
            ContractExchange fetched = proxy(BASE + "/modules/acme/network/aws/1.0.0.tar.gz",
                    recording(asked, upstream(Map.of(), Map.of(DOWNLOAD, source.getKey()),
                            Map.of(source.getValue(), new byte[] {1, 2, 3}))),
                    key -> key.equals("terraform.git-hosts") ? "gitlab.invalid=gitlab, bitbucket.invalid=bitbucket"
                            : null);
            assertThat(fetched.status()).as("%s", source.getKey()).isEqualTo(200);
            assertThat(asked).contains(source.getValue());
            Files.walk(root.resolve("store")).sorted(Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void a_moved_tag_is_refused_once_the_first_fetch_has_recorded_the_ref() throws IOException {
        String git = "git::https://github.invalid/acme/terraform-aws-network?ref=v1.0.0";
        String url = "https://github.invalid/acme/terraform-aws-network/archive/v1.0.0.tar.gz";
        String path = BASE + "/modules/acme/network/aws/1.0.0.tar.gz";
        assertThat(proxy(path, upstream(Map.of(), Map.of(DOWNLOAD, git), Map.of(url, new byte[] {1})), GITHUB)
                .status()).isEqualTo(200);
        // The cached archive goes - evicted, say - and the tag now names other bytes upstream.
        store().delete("terraform/proxied/modules/acme/network/aws/1.0.0.tar.gz");

        ContractExchange moved = proxy(path, upstream(Map.of(), Map.of(DOWNLOAD, git), Map.of(url, new byte[] {2})),
                GITHUB);
        assertThat(moved.status()).as("the moved ref is not served").isNotEqualTo(200);
        assertThat(store().exists("terraform/proxied/modules/acme/network/aws/1.0.0.tar.gz"))
                .as("and nothing is cached in its place").isFalse();
        assertThat(proxy(path, upstream(Map.of(), Map.of(DOWNLOAD, git), Map.of(url, new byte[] {1})), GITHUB)
                .status()).as("the recorded bytes are still accepted").isEqualTo(200);
    }

    @Test
    void a_git_source_nothing_lists_is_refused_when_refusal_is_on() throws IOException {
        String git = "git::ssh://git@github.invalid/acme/terraform-aws-network.git?ref=v1.0.0";
        ContractExchange refused = proxy(BASE + "/v1/modules/acme/network/aws/1.0.0/download",
                upstream(Map.of(), Map.of(DOWNLOAD, git)),
                key -> switch (key) {
                    case "terraform.git-hosts" -> "github.invalid=github";
                    case "terraform.git-refuse-unlisted" -> "true";
                    default -> null;
                });

        assertThat(refused.status()).isNotEqualTo(204);
        assertThat(refused.responseHeader("X-Terraform-Get")).isNull();
    }

    @Test
    void no_git_host_is_listed_unless_the_operator_names_one() throws IOException {
        String git = "git::https://github.com/acme/terraform-aws-network?ref=v1.0.0";
        ContractExchange exchange = proxy(BASE + "/v1/modules/acme/network/aws/1.0.0/download",
                upstream(Map.of(), Map.of(DOWNLOAD, git)));

        assertThat(exchange.responseHeader("X-Terraform-Get")).as("github.com is not fetched from unasked")
                .isEqualTo(git);
    }

    /** A gzipped tar holding one empty file at {@code path}, as a git host's archive of a ref holds a repository. */
    private static byte[] tarGz(String path) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes), "UTF-8")) {
            tar.putArchiveEntry(new TarArchiveEntry(path));
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
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
        return upstream(documents, downloads, Map.of());
    }

    /** {@link #upstream(Map, Map)}, also answering each of {@code archives} with its bytes. */
    private static ProxyFormat.Fetcher upstream(Map<String, String> documents, Map<String, String> downloads,
                                                Map<String, byte[]> archives) {
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (archives.containsKey(url.toString())) {
                    return Optional.of(new ProxyFormat.Fetched(200, archives.get(url.toString()), Map.of()));
                }
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

    /** {@code fetcher}, noting every URL it is asked for in {@code asked}. */
    private static ProxyFormat.Fetcher recording(List<String> asked, ProxyFormat.Fetcher fetcher) {
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders)
                    throws IOException {
                asked.add(url.toString());
                return fetcher.fetch(url, requestHeaders);
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders)
                    throws IOException {
                asked.add(url.toString());
                return fetcher.download(url, requestHeaders);
            }
        };
    }

    private ContractExchange proxy(String path, ProxyFormat.Fetcher fetcher) throws IOException {
        return proxy(path, fetcher, Map.of());
    }

    private ContractExchange proxy(String path, ProxyFormat.Fetcher fetcher, UnaryOperator<String> settings)
            throws IOException {
        return proxy(ContractExchange.of("GET", path).settings(settings), fetcher);
    }

    private ContractExchange proxy(String path, ProxyFormat.Fetcher fetcher, Map<String, String> query)
            throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            exchange = exchange.query(parameter.getKey(), parameter.getValue());
        }
        return proxy(exchange, fetcher);
    }

    private ContractExchange proxy(ContractExchange exchange, ProxyFormat.Fetcher fetcher) throws IOException {
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
