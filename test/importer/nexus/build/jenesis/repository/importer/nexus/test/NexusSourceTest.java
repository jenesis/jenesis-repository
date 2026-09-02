package build.jenesis.repository.importer.nexus.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.importer.nexus.NexusSource;
import build.jenesis.repository.importer.nexus.NexusSourceProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Nexus source walked against a canned components API: it pages by continuation token across two pages, reports
 * each asset with its component format, its path and a lazily-opened download, checkpoints the resume token after each
 * page (and a terminal null), sends basic credentials as an {@code Authorization} header, and raises an
 * {@code IOException} on a failed listing. The credentials never travel to a cross-origin download URL, and a
 * traversal-laced asset path is skipped before it can reach a store write.
 *
 * <p>The listing-supplied {@code downloadUrl} is no longer screened by this connector: the screen rides on the fetcher
 * the source is handed, so the cases that exercise it build the source the way an edge does - through
 * {@link ImportSourceProvider#open}, which wraps the fetcher in {@code ImportScreen}.
 */
class NexusSourceTest {

    private final URI base = URI.create("https://nexus.example/");
    private final String repository = "maven-releases";
    private final String listUrl = "https://nexus.example/service/rest/v1/components?repository=maven-releases";
    private final String page2Url = listUrl + "&continuationToken=tok1";
    private final String downloadUrl = "https://nexus.example/download/lib-1.0.jar";

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** The source built the way an import edge builds one: through {@link ImportSourceProvider#open}, so the fetcher
     *  the connector walks with is screened against the URL the operator submitted. */
    private ImportSource screened(ProxyFormat.Fetcher fetcher, URI submitted) {
        return ImportSourceProvider.open(new NexusSourceProvider(), new ImportRequest(submitted, repository), fetcher);
    }

    /** Walk the whole source, opening every asset - the screen refuses at the fetch, so a refusal only surfaces once
     *  the content is actually opened. */
    private static void drain(ImportSource source) throws IOException {
        source.forEach((format, path, content) -> {
            try (InputStream in = content.open()) {
                in.readAllBytes();
            }
        }, cursor -> { });
    }

    @Test
    void it_pages_components_and_reports_each_asset_with_its_format_and_a_resume_cursor() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page1 = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + downloadUrl + "\"}]}],\"continuationToken\":\"tok1\"}";
        String page2 = "{\"items\":[{\"format\":\"docker\",\"assets\":[{\"path\":\"v2/app/manifests/1.0\","
                + "\"downloadUrl\":\"https://nexus.example/download/manifest\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page1),
                page2Url, ok(page2),
                downloadUrl, new ProxyFormat.Fetched(200, jar, Map.of()),
                "https://nexus.example/download/manifest", new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new NexusSource(base, repository, fetcher).forEach((format, path, content) -> {
            formats.add(format);
            paths.add(path);
            if (path.endsWith("lib-1.0.jar")) {
                try (InputStream in = content.open()) {
                    downloaded.add(in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(formats).containsExactly("maven2", "docker");
        assertThat(paths).containsExactly("org/example/lib/1.0/lib-1.0.jar", "v2/app/manifests/1.0");
        assertThat(cursors).containsExactly("tok1", null);
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).isEqualTo(jar);
    }

    @Test
    void credentials_are_sent_as_a_basic_authorization_header() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(Map.of(listUrl, ok("{\"items\":[],\"continuationToken\":null}")));
        new NexusSource(base, repository, fetcher)
                .withCredentials("user", "secret")
                .forEach((format, path, content) -> { }, cursor -> { });

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.requests).isNotEmpty()
                .allSatisfy(headers -> assertThat(headers.get("Authorization")).isEqualTo(expected));
    }

    @Test
    void credentials_are_not_forwarded_to_a_cross_origin_download_url() throws IOException {
        // The download URL comes off the listing; a compromised or misconfigured Nexus naming another host must not
        // receive the operator's basic credentials - the cross-origin download goes out unauthenticated.
        String foreign = "https://elsewhere.example/download/lib-1.0.jar";
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + foreign + "\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                foreign, new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        new NexusSource(base, repository, fetcher).withCredentials("user", "secret")
                .forEach((format, path, content) -> {
                    try (InputStream in = content.open()) {
                        in.readAllBytes();
                    }
                }, cursor -> { });

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.urls).containsExactly(listUrl, foreign);
        assertThat(fetcher.requests.get(0).get("Authorization")).as("the listing is authenticated").isEqualTo(expected);
        assertThat(fetcher.requests.get(1)).as("the cross-origin download is not").doesNotContainKey("Authorization");
    }

    @Test
    void a_traversal_laced_asset_path_is_skipped() throws IOException {
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":["
                + "{\"path\":\"../../auth/keys\",\"downloadUrl\":\"https://nexus.example/download/evil\"},"
                + "{\"path\":\"org/example/ok.jar\",\"downloadUrl\":\"https://nexus.example/download/ok\"}]}],"
                + "\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                "https://nexus.example/download/ok", new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> paths = new ArrayList<>();
        new NexusSource(base, repository, fetcher).forEach((format, path, content) -> paths.add(path), cursor -> { });

        assertThat(paths).as("the hostile path never reaches the consumer (and is never downloaded)")
                .containsExactly("org/example/ok.jar");
        assertThat(fetcher.urls).containsExactly(listUrl);
    }

    @Test
    void an_absolute_asset_path_from_the_h2_datastore_is_normalised_not_dropped() throws IOException {
        // Nexus 3.71+ (the H2/PostgreSQL datastore that replaced OrientDB) reports asset paths absolute, with a
        // leading slash. safePath's empty-first-segment check would reject the whole asset, so the walk strips the
        // single leading slash to the repository-relative path first - and then still imports and downloads it.
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"/org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + downloadUrl + "\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                downloadUrl, new ProxyFormat.Fetched(200, jar, Map.of())));

        List<String> paths = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new NexusSource(base, repository, fetcher).forEach((format, path, content) -> {
            paths.add(path);
            try (InputStream in = content.open()) {
                downloaded.add(in.readAllBytes());
            }
        }, cursor -> { });

        assertThat(paths).as("the absolute datastore path is normalised to repository-relative, not dropped")
                .containsExactly("org/example/lib/1.0/lib-1.0.jar");
        assertThat(downloaded).containsExactly(jar);
    }

    @Test
    void a_listing_download_url_at_a_private_or_metadata_host_is_refused_before_it_is_fetched() {
        // The downloadUrl comes straight off the (semi-trusted) listing and is fetched as an INITIAL request: the
        // fetcher's SSRF screen only re-judges redirect hops, and the import trigger only vetted the operator's base
        // URL - so a compromised or misconfigured Nexus that points a download at the cloud metadata service or a
        // loopback control plane would otherwise be fetched. The screen refuses it at the fetch and FAILS the walk:
        // dropping the asset counted it nowhere - not imported, not skipped - so a migration whose every download was
        // aimed at a metadata service reported "completed" over an import that had silently taken nothing.
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":["
                + "{\"path\":\"org/example/meta.jar\",\"downloadUrl\":\"http://169.254.169.254/latest/meta-data/\"}]}],"
                + "\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                "http://169.254.169.254/latest/meta-data/", new ProxyFormat.Fetched(200, new byte[]{9}, Map.of())));

        assertThatThrownBy(() -> drain(screened(fetcher, base)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("169.254.169.254");
        assertThat(fetcher.urls).as("the SSRF download URL is never fetched").containsExactly(listUrl);
    }

    @Test
    void a_listing_download_url_that_downgrades_an_https_migration_to_cleartext_is_refused() {
        //, and the sharp one: the base is https and the listing answers http:// on the SAME host. That is
        // cross-origin (the scheme is part of the origin), so credentials were correctly withheld and nothing leaked -
        // but the artifact BYTES were pulled in cleartext and written into the hosted store with no integrity check
        // behind them, so an active intermediary substitutes what the migration imports. The host half is silent here
        // by design: nexus.example is a perfectly ordinary public host.
        String plaintext = "http://nexus.example/download/lib-1.0.jar";
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + plaintext + "\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                plaintext, new ProxyFormat.Fetched(200, "substituted".getBytes(StandardCharsets.UTF_8), Map.of())));

        assertThatThrownBy(() -> drain(screened(fetcher, base)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("downgrades an https migration to cleartext");
        assertThat(fetcher.urls).as("the plaintext download URL is never fetched").containsExactly(listUrl);
    }

    @Test
    void a_same_origin_download_at_a_private_plaintext_base_is_still_fetched() throws IOException {
        // The internal-Nexus migration: the operator points the importer at an on-premises host over plain HTTP (opted
        // in at the edge with block-private-import-hosts=false) and the listing serves same-origin download URLs on
        // that same private host. Neither half bites - the URL goes exactly where the operator already authorised, at
        // the transport they authorised - so the screen leaves the whole walk alone.
        URI internal = URI.create("http://10.0.0.5:8081/");
        String internalList = "http://10.0.0.5:8081/service/rest/v1/components?repository=maven-releases";
        String internalDownload = "http://10.0.0.5:8081/download/lib-1.0.jar";
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + internalDownload + "\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                internalList, ok(page),
                internalDownload, new ProxyFormat.Fetched(200, jar, Map.of())));

        List<String> paths = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        screened(fetcher, internal).forEach((format, path, content) -> {
            paths.add(path);
            try (InputStream in = content.open()) {
                downloaded.add(in.readAllBytes());
            }
        }, cursor -> { });

        assertThat(paths).as("a same-origin private download (the on-prem migration) is not screened out")
                .containsExactly("org/example/lib/1.0/lib-1.0.jar");
        assertThat(downloaded).containsExactly(jar);
    }

    @Test
    void a_failed_listing_is_an_io_exception() {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(500, new byte[0], Map.of())));
        NexusSource source = new NexusSource(base, repository, fetcher);
        assertThatThrownBy(() -> source.forEach((format, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class);
    }
}
