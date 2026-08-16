package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import trigger's screen, both halves under the one {@code block-private-import-hosts} dial.
 *
 * <p><b>The host half:</b> with the anonymous-possible default an unguarded import URL would let an unauthenticated
 * caller aim the server at its own network - a cloud metadata service (169.254.169.254), the loopback control plane
 * (127.0.0.1) or an internal host - so a loopback upstream is refused with a {@code 400}.
 *
 * <p><b>The transport half (D-153):</b> a migration is walked server-side with the operator's upstream username and
 * password attached, so a plaintext source hands that credential to every observer on the path - and it bites exactly
 * where the host half is silent, on a perfectly public host, which is why it was invisible. It is refused with the
 * same {@code 400}, naming the transport rather than sending the operator to look at a host that was never the
 * problem.
 *
 * <p><b>One opt-out:</b> an internal-host migration - typically both private-addressed <em>and</em> plaintext -
 * already sets {@code jenesis.repository.block-private-import-hosts=false}, and the same loopback plaintext import
 * then runs. A fake Nexus on localhost stands in for the private host these cases target.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ImportHostGuardTest {

    private static final String GUARD = "jenesis.repository.block-private-import-hosts";

    @TempDir
    static Path root;

    private WireMockServer nexus;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String upstream;

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        System.setProperty("jenesis.repository.auth", "false");

        nexus = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        nexus.start();
        upstream = "http://localhost:" + nexus.port();
        // An empty first page: a migration that opts past the guard starts, walks nothing and completes cleanly.
        byte[] page = "{\"items\":[],\"continuationToken\":null}".getBytes(StandardCharsets.UTF_8);
        nexus.stubFor(any(urlPathEqualTo("/service/rest/v1/components"))
                .willReturn(aResponse().withStatus(200).withBody(page)));

        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void tearDown() {
        running.close();
        nexus.stop();
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty(GUARD);
    }

    @Test
    public void a_loopback_import_is_refused_by_default() throws Exception {
        System.setProperty(GUARD, "true");                     // the shipped default; pinned to be explicit
        // https, so this cell still exercises the HOST half: the transport is judged first, and a plaintext URL would
        // be refused before the loopback address was ever resolved. Nothing connects - the screen refuses at the edge.
        HttpResponse<String> refused = post("{\"source\":\"nexus\",\"url\":\"https://localhost:" + nexus.port()
                + "\",\"repository\":\"releases\"}");
        assertThat(refused.statusCode()).as("a loopback upstream is an SSRF vector, refused up front").isEqualTo(400);
        assertThat(refused.body()).contains("private, loopback").contains("public host");
    }

    @Test
    public void a_plaintext_import_url_is_refused_by_default_even_to_a_public_host() throws Exception {
        // D-153. The host half has nothing to say about incumbent.example, and the request below would have carried
        // the operator's upstream password to it in the clear. The refusal names the transport, so an operator whose
        // source is plaintext on a public host is not sent to go and look at its host.
        System.setProperty(GUARD, "true");
        HttpResponse<String> refused = post("{\"source\":\"nexus\",\"url\":\"http://incumbent.example\","
                + "\"repository\":\"releases\",\"username\":\"operator\",\"password\":\"s3cr3t\"}");
        assertThat(refused.statusCode()).as("a plaintext migration leaks the upstream credential").isEqualTo(400);
        assertThat(refused.body()).contains("not https").doesNotContain("private, loopback");
    }

    @Test
    public void the_opt_out_allows_an_internal_plaintext_host_migration() throws Exception {
        // The one dial covers both halves: the on-prem migration this exists for is private-addressed AND plaintext,
        // and an operator able to permit one and not the other is an operator who can end up sending a credential in
        // the clear while the guard still reads as on.
        System.setProperty(GUARD, "false");                    // explicit internal-host opt-out
        HttpResponse<String> accepted = post("{\"source\":\"nexus\",\"url\":\"" + upstream
                + "\",\"repository\":\"releases\"}");
        assertThat(accepted.statusCode()).as("the opt-out lets the same loopback plaintext import run").isEqualTo(202);
    }

    @Test
    public void a_malformed_url_is_a_bad_request_even_with_the_screen_opted_out() throws Exception {
        // With the dial off the screen returns without parsing, so this URL reached URI.create unguarded and escaped
        // as an unmapped 500 with no body - an operator learned only that something went wrong.
        System.setProperty(GUARD, "false");
        HttpResponse<String> refused = post("{\"source\":\"nexus\",\"url\":\"ht tp://incumbent.example\","
                + "\"repository\":\"releases\"}");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("malformed");
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/admin/import"))
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }
}
