package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.pypi.PyPiEnumeration;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PEP 503 walk over canned pages - no server, no network: the root project list is followed to each project's
 * page, relative and absolute-path project links both resolve, a file link resolves against the page it appears on
 * (an off-host file URL kept as-is) with its {@code #sha256} fragment dropped, a missing root fails eagerly, and a
 * project page failing mid-walk surfaces once the stream reaches it.
 */
class PyPiEnumerationTest {

    @Test
    void the_walk_lists_every_projects_files() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/simple/", 200,
                        "<html><body><a href=\"alpha/\">alpha</a><a href=\"/simple/beta/\">beta</a></body></html>")
                .on("http://source.local/simple/alpha/", 200,
                        "<a href=\"alpha-1.0-py3-none-any.whl#sha256=abc\">alpha-1.0-py3-none-any.whl</a>")
                .on("http://source.local/simple/beta/", 200,
                        "<a href=\"https://files.other.host/pkg/beta-2.0.tar.gz#sha256=def\">beta-2.0.tar.gz</a>");

        List<Map.Entry<String, URI>> entries = PyPiEnumeration
                .enumerate(fetcher, URI.create("http://source.local"), false).toList();

        assertThat(entries).containsExactly(
                Map.entry("alpha/alpha-1.0-py3-none-any.whl",
                        URI.create("http://source.local/simple/alpha/alpha-1.0-py3-none-any.whl")),
                Map.entry("beta/beta-2.0.tar.gz", URI.create("https://files.other.host/pkg/beta-2.0.tar.gz")));
    }

    @Test
    void a_file_url_resolving_to_a_private_host_is_skipped() throws IOException {
        // A hostile/compromised index page links a file at the cloud metadata service (169.254.169.254, link-local):
        // once enumerate() is wired into the importer the emitted URL becomes a server-side download target, so it must
        // be screened out by the same PrivateHosts SSRF guard the proxy applies, while a legitimate public off-host
        // file (files.pythonhosted.org-style) is kept. Mirrors the Composer enumeration SSRF test.
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/simple/", 200, "<a href=\"alpha/\">alpha</a>")
                .on("http://source.local/simple/alpha/", 200,
                        "<a href=\"http://169.254.169.254/latest/meta-data/evil-1.0-py3-none-any.whl\">evil</a>"
                                + "<a href=\"https://files.other.host/pkg/alpha-1.0.tar.gz#sha256=abc\">alpha</a>");

        List<Map.Entry<String, URI>> entries = PyPiEnumeration
                .enumerate(fetcher, URI.create("http://source.local"), false).toList();

        assertThat(entries)
                .as("the link-local metadata-service file URL is screened out; the public off-host file is kept")
                .containsExactly(Map.entry("alpha/alpha-1.0.tar.gz",
                        URI.create("https://files.other.host/pkg/alpha-1.0.tar.gz")));
    }

    @Test
    void a_missing_root_index_fails_eagerly() {
        CannedFetcher fetcher = new CannedFetcher().on("http://source.local/simple/", 404, "");
        assertThatThrownBy(() -> PyPiEnumeration.enumerate(fetcher, URI.create("http://source.local"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    void a_project_page_failing_mid_walk_surfaces_when_reached() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/simple/", 200, "<a href=\"alpha/\">alpha</a><a href=\"beta/\">beta</a>")
                .on("http://source.local/simple/alpha/", 200, "<a href=\"a.whl\">a.whl</a>")
                .on("http://source.local/simple/beta/", 500, "");
        Stream<Map.Entry<String, URI>> walk = PyPiEnumeration.enumerate(fetcher, URI.create("http://source.local"), false);
        assertThatThrownBy(walk::toList)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("500");
    }
}
