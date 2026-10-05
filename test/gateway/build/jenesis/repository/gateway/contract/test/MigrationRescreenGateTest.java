package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.gateway.MigrationRescreenTaskProvider;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration re-screen's two gate flavours over a configured advisory feed: an artifact uploaded here is
 * re-screened asking no feed, since a version published here is asked of none, and a copy fetched from an upstream
 * is re-screened asking it.
 */
class MigrationRescreenGateTest {

    private static final ComplianceGate.Subject SUBJECT =
            new ComplianceGate.Subject("Maven", "com.acme:lib", "1.0", List.of());

    private HttpServer osv;
    private final AtomicInteger asked = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        osv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        osv.createContext("/v1/query", exchange -> {
            asked.incrementAndGet();
            byte[] body = "{\"vulns\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        osv.start();
    }

    @AfterEach
    void stop() {
        osv.stop(0);
    }

    @Test
    void the_publish_flavour_asks_no_feed_and_the_proxy_flavour_does() {
        Map<String, String> config = Map.of("osv", "true",
                "osv-endpoint", "http://localhost:" + osv.getAddress().getPort());

        MigrationRescreenTaskProvider.gate(config::get, GatePolicyProvider.Path.PUBLISH).assess(SUBJECT);
        assertThat(asked).as("an uploaded artifact's re-screen asks no feed").hasValue(0);

        MigrationRescreenTaskProvider.gate(config::get, GatePolicyProvider.Path.PROXY).assess(SUBJECT);
        assertThat(asked.get()).as("a fetched copy's re-screen asks the configured feed").isPositive();
    }
}
