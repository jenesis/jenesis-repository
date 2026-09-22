package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.debian.DebianEnumeration;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The apt walk over canned indexes - no server, no network: suites come from the {@code dists/} autoindex (a
 * parent link ignored), each suite's {@code Release} names its {@code Packages} indexes with the gzipped variant
 * preferred per directory, stanzas stream their {@code Filename} fields, a package listed for two architectures is
 * emitted once, and a source without a {@code dists/} listing fails eagerly with the honest constraint.
 */
class DebianEnumerationTest {

    private static final String RELEASE = """
            Suite: stable
            Components: main
            Architectures: amd64 all
            SHA256:
             aaaa 100 main/binary-amd64/Packages
             bbbb 80 main/binary-amd64/Packages.gz
             cccc 90 main/binary-all/Packages
            """;

    private static final String AMD64 = """
            Package: hello
            Version: 1.0
            Architecture: all
            Filename: pool/main/h/hello/hello_1.0_all.deb

            Package: tool
            Version: 2.0
            Architecture: amd64
            Filename: pool/main/t/tool/tool_2.0_amd64.deb
            """;

    private static final String ALL = """
            Package: hello
            Version: 1.0
            Architecture: all
            Filename: pool/main/h/hello/hello_1.0_all.deb
            """;

    @Test
    void the_walk_streams_the_pool_paths_once() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/debian/dists/", 200,
                        "<html><a href=\"../\">../</a><a href=\"stable/\">stable/</a></html>")
                .on("http://source.local/debian/dists/stable/Release", 200, RELEASE)
                .on("http://source.local/debian/dists/stable/main/binary-amd64/Packages.gz", 200, gzip(AMD64))
                .on("http://source.local/debian/dists/stable/main/binary-all/Packages", 200, ALL);

        List<Map.Entry<String, URI>> entries = DebianEnumeration
                .enumerate(fetcher, URI.create("http://source.local/debian"), false).toList();

        assertThat(entries).containsExactly(
                Map.entry("pool/main/h/hello/hello_1.0_all.deb",
                        URI.create("http://source.local/debian/pool/main/h/hello/hello_1.0_all.deb")),
                Map.entry("pool/main/t/tool/tool_2.0_amd64.deb",
                        URI.create("http://source.local/debian/pool/main/t/tool/tool_2.0_amd64.deb")));
        assertThat(fetcher.urls)
                .as("the gzipped index is preferred over the plain one in the same directory")
                .doesNotContain("http://source.local/debian/dists/stable/main/binary-amd64/Packages");
    }

    @Test
    void a_filename_resolving_to_a_private_host_is_skipped() throws IOException {
        // A hostile/compromised mirror's Packages stanza carries an ABSOLUTE Filename at the cloud metadata service
        // (169.254.169.254, link-local); java.net.URI.resolve returns an absolute argument as-is, so once enumerate()
        // is wired into the importer that becomes a server-side download target. It must be screened out by the same
        // PrivateHosts SSRF guard the proxy applies, while the ordinary pool-relative filename is kept. Mirrors the
        // Composer enumeration SSRF test.
        String evil = """
                Package: hello
                Version: 1.0
                Architecture: all
                Filename: pool/main/h/hello/hello_1.0_all.deb

                Package: pwn
                Version: 6.6.6
                Architecture: all
                Filename: http://169.254.169.254/latest/meta-data/iam/security-credentials/role
                """;
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/debian/dists/", 200,
                        "<html><a href=\"../\">../</a><a href=\"stable/\">stable/</a></html>")
                .on("http://source.local/debian/dists/stable/Release", 200, """
                        Suite: stable
                        Components: main
                        Architectures: all
                        SHA256:
                         cccc 90 main/binary-all/Packages
                        """)
                .on("http://source.local/debian/dists/stable/main/binary-all/Packages", 200, evil);

        List<Map.Entry<String, URI>> entries = DebianEnumeration
                .enumerate(fetcher, URI.create("http://source.local/debian"), false).toList();

        assertThat(entries)
                .as("the absolute Filename resolving to the link-local metadata service is skipped; the pool file is kept")
                .containsExactly(Map.entry("pool/main/h/hello/hello_1.0_all.deb",
                        URI.create("http://source.local/debian/pool/main/h/hello/hello_1.0_all.deb")));
    }

    @Test
    void a_source_without_a_dists_listing_fails_eagerly() {
        CannedFetcher fetcher = new CannedFetcher().on("http://source.local/debian/dists/", 404, "");
        assertThatThrownBy(() -> DebianEnumeration.enumerate(fetcher, URI.create("http://source.local/debian"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    void a_listing_without_suites_fails_with_the_honest_constraint() {
        CannedFetcher fetcher = new CannedFetcher()
                .on("http://source.local/debian/dists/", 200, "<html><a href=\"../\">../</a></html>");
        assertThatThrownBy(() -> DebianEnumeration.enumerate(fetcher, URI.create("http://source.local/debian"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No suites");
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
