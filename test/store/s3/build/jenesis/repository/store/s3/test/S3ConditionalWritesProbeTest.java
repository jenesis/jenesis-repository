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
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code s3} provider refuses to start on an endpoint that ignores write preconditions, and starts on one that
 * honours them - decided at boot by two creates of one key, over a WireMock endpoint that plays each part. Needs no
 * Docker, so it always runs; the real backends are held to the same answer by the store contract's compare-and-set
 * properties over MinIO, Azurite and the storage testbench.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ConditionalWritesProbeTest {

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
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void an_endpoint_that_honours_the_precondition_lets_the_node_start() {
        server.stubFor(put(urlPathMatching("/repo/.system/probe/.*")).inScenario("probe")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("If-None-Match", equalTo("*"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"1\""))
                .willSetStateTo("created"));
        server.stubFor(put(urlPathMatching("/repo/.system/probe/.*")).inScenario("probe")
                .whenScenarioStateIs("created")
                .withHeader("If-None-Match", equalTo("*"))
                .willReturn(aResponse().withStatus(412).withHeader("Content-Type", "application/xml")
                        .withBody(PRECONDITION_FAILED)));

        ArtifactStore store = new S3ArtifactStoreProvider().create(config()::get);

        assertThat(store).as("the store was created after the probe").isNotNull();
        assertThat(server.findAll(putRequestedFor(urlPathMatching("/repo/.system/probe/.*"))))
                .as("two creates of the one probe key, both under If-None-Match: *").hasSize(2);
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching("/repo/.system/probe/.*"))))
                .as("the probe key is deleted afterwards").hasSize(1);
    }

    @Test
    public void an_endpoint_that_ignores_the_precondition_stops_the_node() {
        server.stubFor(put(urlPathMatching("/repo/.system/probe/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"1\"")));   // 200 to both creates

        assertThatThrownBy(() -> new S3ArtifactStoreProvider().create(config()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ignores write preconditions")
                .hasMessageContaining("localhost:" + server.port());
        assertThat(server.findAll(deleteRequestedFor(urlPathMatching("/repo/.system/probe/.*"))))
                .as("the probe key is deleted even when the node refuses to start").hasSize(1);
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
