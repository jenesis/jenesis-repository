package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.composer.ComposerEnumeration;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Composer-v2 walk over canned documents - no server, no network: names come from the root's
 * {@code available-packages} or its {@code list} endpoint, each name's {@code p2} file (and {@code ~dev} companion,
 * absent here) contributes one entry per version naming its own zip dist, a dist redirecting to a private third
 * host is skipped by the same SSRF rule the proxy applies while one on the submitted source's own authority is
 * kept, and a repository advertising neither listing fails eagerly with the honest constraint.
 */
class ComposerEnumerationTest {

    private static final String ROOT = "http://source.local/composer/composer";

    private static final String P2 = """
            {"packages":{"acme/widget":[
              {"version":"1.0.0","dist":{"type":"zip","url":"%s/dists/acme/widget/1.0.0.zip"}},
              {"version":"1.1.0","dist":{"type":"zip","url":"http://169.254.169.254/latest/meta-data"}},
              {"version":"1.2.0"}
            ]}}""".formatted(ROOT);

    @Test
    void available_packages_drive_the_walk_and_the_ssrf_guard_holds() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on(ROOT + "/packages.json", 200, "{\"metadata-url\":\"/composer/composer/p2/%package%.json\","
                        + "\"available-packages\":[\"acme/widget\"]}")
                .on("http://source.local/composer/composer/p2/acme/widget.json", 200, P2)
                .on("http://source.local/composer/composer/p2/acme/widget~dev.json", 404, "");

        List<Map.Entry<String, URI>> entries = ComposerEnumeration
                .enumerate(fetcher, URI.create(ROOT), false).toList();

        assertThat(entries)
                .as("the private third-host dist and the dist-less version are skipped")
                .containsExactly(Map.entry("acme/widget/1.0.0.zip",
                        URI.create(ROOT + "/dists/acme/widget/1.0.0.zip")));
    }

    @Test
    void the_list_endpoint_stands_in_when_no_inline_listing_exists() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on(ROOT + "/packages.json", 200, "{\"metadata-url\":\"/composer/composer/p2/%package%.json\","
                        + "\"list\":\"/composer/composer/list.json\"}")
                .on("http://source.local/composer/composer/list.json", 200,
                        "{\"packageNames\":[\"acme/widget\"]}")
                .on("http://source.local/composer/composer/p2/acme/widget.json", 200, P2)
                .on("http://source.local/composer/composer/p2/acme/widget~dev.json", 404, "");

        List<Map.Entry<String, URI>> entries = ComposerEnumeration
                .enumerate(fetcher, URI.create(ROOT), false).toList();

        assertThat(entries).extracting(Map.Entry::getKey).containsExactly("acme/widget/1.0.0.zip");
    }

    @Test
    void a_version_with_a_malformed_dist_url_is_skipped() throws IOException {
        // A dist url that is not a well-formed URI (URI.create throws IllegalArgumentException) is skipped like a
        // missing dist, never aborting the walk - one poisoned entry in an upstream's p2 file cannot deny the
        // migration of the package's well-formed versions.
        String p2 = """
                {"packages":{"acme/widget":[
                  {"version":"1.0.0","dist":{"type":"zip","url":"%s/dists/acme/widget/1.0.0.zip"}},
                  {"version":"1.3.0","dist":{"type":"zip","url":"http://source.local/d ist/ bad .zip"}}
                ]}}""".formatted(ROOT);
        CannedFetcher fetcher = new CannedFetcher()
                .on(ROOT + "/packages.json", 200, "{\"metadata-url\":\"/composer/composer/p2/%package%.json\","
                        + "\"available-packages\":[\"acme/widget\"]}")
                .on("http://source.local/composer/composer/p2/acme/widget.json", 200, p2)
                .on("http://source.local/composer/composer/p2/acme/widget~dev.json", 404, "");

        List<Map.Entry<String, URI>> entries = ComposerEnumeration
                .enumerate(fetcher, URI.create(ROOT), false).toList();

        assertThat(entries)
                .as("the malformed-url version is skipped; the well-formed version still enumerates")
                .containsExactly(Map.entry("acme/widget/1.0.0.zip",
                        URI.create(ROOT + "/dists/acme/widget/1.0.0.zip")));
    }

    @Test
    void a_repository_without_a_listing_fails_with_the_honest_constraint() {
        CannedFetcher fetcher = new CannedFetcher()
                .on(ROOT + "/packages.json", 200, "{\"metadata-url\":\"/composer/composer/p2/%package%.json\"}");
        assertThatThrownBy(() -> ComposerEnumeration.enumerate(fetcher, URI.create(ROOT), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not enumerable");
    }
}
