package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.s3.S3ArtifactStoreProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code s3} provider refuses to start on an endpoint that ignores a write precondition, and starts on one that
 * honours both - decided at boot by two creates and two replaces of one key, over a WireMock endpoint that plays each
 * part: the compliant one, the one that ignores everything (OVHcloud's shape), the one that honours only-if-absent but
 * not replace-if-unchanged (Exoscale's shape), and the compliant one with the probe switched off. Needs no Docker, so
 * it always runs; the real backends are held to the same answer by the store contract's compare-and-set properties
 * over MinIO, Azurite and the storage testbench, each of which boots through this probe.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ConditionalWritesProbeTest {

    private static final String PROBE = "/repo/.system/probe/.*";
    private static final String PRECONDITION_FAILED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
            + "<Code>PreconditionFailed</Code><Message>At least one of the pre-conditions you specified did not hold"
            + "</Message></Error>";

    private WireMockServer server;

    @BeforeAll
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
    }

    @BeforeEach
    public void reset() {
        server.resetAll();
        server.stubFor(put(urlPathEqualTo("/repo")).willReturn(aResponse().withStatus(200)));       // createBucket
        server.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(204)));
        // The version read between the creates and the replaces: the token the first create left.
        server.stubFor(head(urlPathMatching(PROBE)).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"1\"").withHeader("Content-Length", "24")));
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** The create leg as a compliant endpoint plays it: the first only-if-absent create lands, the second is refused. */
    private void honoursCreate() {
        server.stubFor(put(urlPathMatching(PROBE)).inScenario("create")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("If-None-Match", equalTo("*"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"1\""))
                .willSetStateTo("created"));
        server.stubFor(put(urlPathMatching(PROBE)).inScenario("create")
                .whenScenarioStateIs("created")
                .withHeader("If-None-Match", equalTo("*"))
                .willReturn(aResponse().withStatus(412).withHeader("Content-Type", "application/xml")
                        .withBody(PRECONDITION_FAILED)));
    }

    /** The replace leg as a compliant endpoint plays it: the replace under the current token lands, the one under
     *  the now-stale token is refused. */
    private void honoursReplace() {
        server.stubFor(put(urlPathMatching(PROBE)).inScenario("replace")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("If-Match", equalTo("\"1\""))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"2\""))
                .willSetStateTo("replaced"));
        server.stubFor(put(urlPathMatching(PROBE)).inScenario("replace")
                .whenScenarioStateIs("replaced")
                .withHeader("If-Match", equalTo("\"1\""))
                .willReturn(aResponse().withStatus(412).withHeader("Content-Type", "application/xml")
                        .withBody(PRECONDITION_FAILED)));
    }

    @Test
    public void an_endpoint_that_honours_both_preconditions_lets_the_node_start() {
        honoursCreate();
        honoursReplace();

        ArtifactStore store = new S3ArtifactStoreProvider().create(config()::get);

        assertThat(store).as("the store was created after the probe").isNotNull();
        assertThat(server.findAll(putRequestedFor(urlPathMatching(PROBE))))
                .as("two creates under If-None-Match: * and two replaces under If-Match, on the one probe key").hasSize(4);
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching(PROBE))))
                .as("the probe key is deleted afterwards").hasSize(1);
    }

    @Test
    public void an_endpoint_that_ignores_every_precondition_stops_the_node() {
        server.stubFor(put(urlPathMatching(PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"1\"")));   // 200 to everything

        assertThatThrownBy(() -> new S3ArtifactStoreProvider().create(config()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ignores write preconditions")
                .hasMessageContaining("localhost:" + server.port());
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching(PROBE))))
                .as("the probe key is deleted even when the node refuses to start").hasSize(1);
    }

    @Test
    public void an_endpoint_that_honours_only_if_absent_but_not_replace_if_unchanged_stops_the_node() {
        honoursCreate();
        server.stubFor(put(urlPathMatching(PROBE)).withHeader("If-Match", equalTo("\"1\""))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"2\"")));   // If-Match ignored

        assertThatThrownBy(() -> new S3ArtifactStoreProvider().create(config()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale token")
                .hasMessageContaining("localhost:" + server.port());
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching(PROBE))))
                .as("the probe key is deleted even when the node refuses to start").hasSize(1);
    }

    @Test
    public void the_probe_can_be_switched_off_and_then_writes_nothing() {
        server.stubFor(put(urlPathMatching(PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"1\"")));   // would fail the probe
        Map<String, String> config = config();
        config.put(S3ArtifactStoreProvider.PROBE_KEY, "false");

        ArtifactStore store = new S3ArtifactStoreProvider().create(config::get);

        assertThat(store).as("the node starts over an endpoint it was told not to probe").isNotNull();
        assertThat(server.findAll(putRequestedFor(urlPathMatching(PROBE)))).as("no probe write").isEmpty();
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching(PROBE)))).as("no probe delete").isEmpty();
    }

    private Map<String, String> config() {
        Map<String, String> config = new HashMap<>();
        config.put(S3ArtifactStoreProvider.BUCKET_KEY, "repo");
        config.put(S3ArtifactStoreProvider.ENDPOINT_KEY, "http://localhost:" + server.port());
        config.put(S3ArtifactStoreProvider.ALLOW_INSECURE_KEY, "true");
        config.put("jenreg.s3.access-key-id", "ak");
        config.put("jenreg.s3.secret-access-key", "sk");
        return config;
    }
}
