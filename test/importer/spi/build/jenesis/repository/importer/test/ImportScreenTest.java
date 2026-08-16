package build.jenesis.repository.importer.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one import screen, in both its shapes. The <em>edge</em> shape judges the URL an operator submitted under the
 * {@code block-private-import-hosts} dial; the <em>fetch</em> shape judges every URL a source then hands back, against
 * the URL that dial admitted, and it needs no dial of its own because the authorisation level is already stated by the
 * submitted URL. The fetch shape is the one D-152 was missing: a screen that judges only what the operator typed
 * judges the one URL a hostile source does not control.
 */
class ImportScreenTest {

    private static final URI HTTPS = URI.create("https://incumbent.example/");
    private static final URI PLAINTEXT = URI.create("http://10.0.0.5:8081/");

    /** A fetcher that records every URL it is asked for and answers each leg, so a refusal is distinguishable from a
     *  fetch that happened and returned nothing. */
    private static final class Recording implements ProxyFormat.Fetcher {

        private final List<String> urls = new ArrayList<>();

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
            urls.add(url.toString());
            return Optional.of(new ProxyFormat.Fetched(200, new byte[0], Map.of()));
        }

        @Override
        public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
            urls.add(url.toString());
            return Optional.of(new ProxyFormat.Download(200, InputStream.nullInputStream(), Map.of()));
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) {
            urls.add(url.toString());
            return Optional.of(new ProxyFormat.Head(200, Map.of()));
        }
    }

    // ---- the fetch shape ------------------------------------------------------------------------------------

    @Test
    void a_plaintext_url_under_an_https_migration_is_refused_even_on_the_very_same_host() {
        // D-152's vector. The scheme is part of the origin, so this URL is cross-origin and the credential half
        // correctly withheld the password - nothing leaks. What travels in the clear is the artifact body, which is
        // then written into the hosted store, and no integrity check stands behind it: the checksums a migration can
        // see are served by the same party that serves the bytes.
        String refusal = ImportScreen.refusalReason(HTTPS, URI.create("http://incumbent.example/lib-1.0.jar"));
        assertThat(refusal).contains("downgrades an https migration to cleartext").contains("substituted");
    }

    @Test
    void a_cross_origin_url_at_an_internal_host_is_refused() {
        assertThat(ImportScreen.refusalReason(HTTPS, URI.create("https://127.0.0.1:8081/x.jar")))
                .isEqualTo("it is a cross-origin URL to a private, loopback or cloud-metadata host");
        assertThat(ImportScreen.refusalReason(HTTPS, URI.create("https://169.254.169.254/latest/meta-data/")))
                .isEqualTo("it is a cross-origin URL to a private, loopback or cloud-metadata host");
    }

    @Test
    void a_non_http_url_is_refused_whatever_the_migration() {
        assertThat(ImportScreen.refusalReason(HTTPS, URI.create("file:///etc/passwd")))
                .contains("not an http(s) URL");
        assertThat(ImportScreen.refusalReason(PLAINTEXT, URI.create("ftp://incumbent.example/x.jar")))
                .contains("not an http(s) URL");
    }

    @Test
    void a_same_origin_url_is_admitted_at_whatever_level_the_operator_authorised() {
        // The on-premises migration: the operator pointed the importer at a private host over plain HTTP (having
        // opted out at the edge), and the listing serves its own download URLs there. Those go exactly where the
        // operator already authorised, at the transport they authorised, so neither half bites.
        assertThat(ImportScreen.refusalReason(PLAINTEXT, URI.create("http://10.0.0.5:8081/download/lib.jar"))).isNull();
        assertThat(ImportScreen.refusalReason(HTTPS, URI.create("https://incumbent.example/download/lib.jar"))).isNull();
    }

    @Test
    void a_cross_origin_public_url_stays_admissible_because_a_redirecting_cdn_is_not_the_vector() {
        // A Nexus that hands its downloads to an object store is ordinary, so the screen refuses a downgrade and an
        // internal host - not simply "somewhere else".
        assertThat(ImportScreen.refusalReason(HTTPS, URI.create("https://cdn.example/lib-1.0.jar"))).isNull();
    }

    @Test
    void a_plaintext_migration_may_still_reach_a_plaintext_third_host() {
        // The operator who opted into cleartext at the edge opted into it, and a screen that pretended otherwise
        // would refuse the very migration the dial exists to permit.
        assertThat(ImportScreen.refusalReason(PLAINTEXT, URI.create("http://mirror.example/lib.jar"))).isNull();
    }

    // ---- the fetch shape, as it actually rides ---------------------------------------------------------------

    @Test
    void the_screen_refuses_on_every_leg_before_the_transport_is_reached() throws IOException {
        Recording transport = new Recording();
        ProxyFormat.Fetcher screened = ImportScreen.around(transport, HTTPS);
        URI plaintext = URI.create("http://incumbent.example/lib-1.0.jar");

        assertThatThrownBy(() -> screened.fetch(plaintext, Map.of())).isInstanceOf(ImportFailure.class);
        assertThatThrownBy(() -> screened.download(plaintext, Map.of())).isInstanceOf(ImportFailure.class);
        assertThatThrownBy(() -> screened.head(plaintext, Map.of())).isInstanceOf(ImportFailure.class);
        assertThat(transport.urls).as("a refused URL never reaches the transport at all").isEmpty();

        URI allowed = URI.create("https://incumbent.example/lib-1.0.jar");
        assertThat(screened.fetch(allowed, Map.of())).isPresent();
        assertThat(screened.download(allowed, Map.of())).isPresent();
        assertThat(screened.head(allowed, Map.of())).isPresent();
        assertThat(transport.urls).as("all three legs are delegated, none derived from another")
                .containsExactly(allowed.toString(), allowed.toString(), allowed.toString());
    }

    @Test
    void the_screen_refuses_with_an_import_failure_so_a_walk_fails_visibly_rather_than_dropping_the_asset() {
        // A dropped asset is counted nowhere - not imported, not skipped - so a migration whose downloads were all
        // aimed at a metadata service reported "completed" over an import that had silently taken nothing.
        ProxyFormat.Fetcher screened = ImportScreen.around(new Recording(), HTTPS);
        assertThatThrownBy(() -> screened.download(URI.create("http://169.254.169.254/latest/meta-data/"), Map.of()))
                .isInstanceOf(ImportFailure.class)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining(HTTPS.toString());
    }

    @Test
    void a_fetcher_that_fetches_nothing_is_not_wrapped() {
        assertThat(ImportScreen.around(ProxyFormat.Fetcher.NONE, HTTPS)).isSameAs(ProxyFormat.Fetcher.NONE);
    }

    @Test
    void a_screen_with_nothing_to_judge_against_throws_rather_than_passing_the_transport_through() {
        // The failure mode this class exists to prevent is a screen that quietly becomes a no-op, so the one input it
        // cannot do without is refused loudly rather than defaulted away (§9).
        assertThatThrownBy(() -> ImportScreen.around(new Recording(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the URL the operator submitted");
    }

    @Test
    void a_source_is_built_with_the_screened_fetcher_when_an_edge_opens_it() {
        // ImportSourceProvider.open is the seam: the provider sees a screened fetcher without asking for one, which is
        // why a connector added tomorrow arrives screened without knowing this class exists.
        Recording transport = new Recording();
        List<ProxyFormat.Fetcher> handed = new ArrayList<>();
        ImportSourceProvider provider = new ImportSourceProvider() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
                handed.add(fetcher);
                return (consumer, checkpoint) -> checkpoint.reached(null);
            }
        };

        ImportSourceProvider.open(provider, new ImportRequest(HTTPS, "libs"), transport);

        assertThat(handed).singleElement().isNotSameAs(transport).isInstanceOf(ImportScreen.class);
    }

    // ---- the edge shape -------------------------------------------------------------------------------------

    @Test
    void a_plaintext_submitted_url_is_refused_by_default_even_to_a_public_host() {
        // The half the free edge was missing (D-153): the private-host screen is silent about a public host, and the
        // request below attaches the operator's upstream username and password.
        assertThat(ImportScreen.refusalReason("http://incumbent.example/", true))
                .isEqualTo("the URL is not https (scheme 'http')");
    }

    @Test
    void the_dial_is_the_whole_opt_out_and_covers_both_halves() {
        // One dial, deliberately: an operator able to permit cleartext but not internal hosts (or the reverse) is an
        // operator who can send a credential in the clear while the guard still reads as on.
        assertThat(ImportScreen.refusalReason("http://incumbent.example/", false)).isNull();
        assertThat(ImportScreen.refusalReason("http://127.0.0.1:8081/", false)).isNull();
    }

    @Test
    void a_submitted_url_at_an_internal_host_is_refused() {
        assertThat(ImportScreen.refusalReason("https://127.0.0.1:8081/", true))
                .isEqualTo("the host resolves to a private, loopback, link-local or cloud-metadata address");
    }

    @Test
    void the_transport_is_judged_first_so_a_plaintext_url_is_never_resolved_and_names_the_right_half() {
        // An operator whose source is plaintext on a public host must not be told to go and look at its host.
        assertThat(ImportScreen.refusalReason("http://127.0.0.1:8081/", true))
                .isEqualTo("the URL is not https (scheme 'http')");
    }

    @Test
    void an_unresolvable_host_stays_admissible_so_the_sources_own_probe_gives_the_better_message() {
        assertThat(ImportScreen.refusalReason("https://no-such-host.example/", true)).isNull();
    }

    @Test
    void a_malformed_or_schemeless_or_hostless_submitted_url_is_refused() {
        assertThat(ImportScreen.refusalReason("ht tp://incumbent.example/", true)).isEqualTo("the URL is malformed");
        assertThat(ImportScreen.refusalReason("incumbent.example/libs", true)).contains("not an http(s) URL");
        assertThat(ImportScreen.refusalReason("file:///etc/passwd", true)).contains("not an http(s) URL");
        assertThat(ImportScreen.refusalReason("https:///libs", true)).isEqualTo("the URL names no host");
    }
}
