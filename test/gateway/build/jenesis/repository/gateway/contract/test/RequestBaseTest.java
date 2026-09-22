package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.format.FormatExchange;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a generated index's absolute URLs point, in the order {@link RequestBase} resolves it: the operator's
 * public URL outright, else a trusted proxy's forwarded scheme and host, else the request's own - and never a
 * forwarded header from a peer that is not a trusted proxy, which is the hole a client would use to have the
 * registry publish an index pointing wherever it likes.
 */
class RequestBaseTest {

    @Test
    void the_request_itself_names_the_base_for_a_deployment_reached_directly() {
        assertThat(RequestBase.of(exchange("https", "203.0.113.9", Map.of("Host", "repo.example.com"), Map.of())))
                .isEqualTo("https://repo.example.com");
    }

    @Test
    void a_trusted_proxys_forwarded_scheme_and_host_are_believed() {
        FormatExchange exchange = exchange("http", "10.0.0.5",
                Map.of("Host", "repo-node-1:8080", "X-Forwarded-Proto", "https, http",
                        "X-Forwarded-Host", "repo.example.com"),
                Map.of(RequestBase.TRUSTED_PROXIES, "10.0.0.0/8, 192.168.1.1"));
        assertThat(RequestBase.of(exchange))
                .as("the first hop of each forwarded header, which is the front door's")
                .isEqualTo("https://repo.example.com");
    }

    @Test
    void a_forwarded_header_from_anyone_else_is_ignored() {
        FormatExchange spoofed = exchange("http", "203.0.113.9",
                Map.of("Host", "repo-node-1:8080", "X-Forwarded-Proto", "https",
                        "X-Forwarded-Host", "evil.example.net"),
                Map.of(RequestBase.TRUSTED_PROXIES, "10.0.0.0/8"));
        assertThat(RequestBase.of(spoofed))
                .as("a client's own forwarded headers never decide where an index sends everyone")
                .isEqualTo("http://repo-node-1:8080");
        FormatExchange untrusting = exchange("http", "10.0.0.5",
                Map.of("Host", "repo-node-1:8080", "X-Forwarded-Proto", "https"), Map.of());
        assertThat(RequestBase.of(untrusting))
                .as("with no trusted proxies configured, nobody's forwarded headers are believed")
                .isEqualTo("http://repo-node-1:8080");
    }

    @Test
    void the_operators_public_url_wins_outright() {
        FormatExchange exchange = exchange("http", "10.0.0.5",
                Map.of("Host", "repo-node-1:8080", "X-Forwarded-Proto", "https"),
                Map.of(RequestBase.PUBLIC_URL, "https://artifacts.example.com/registry/",
                        RequestBase.TRUSTED_PROXIES, "10.0.0.0/8"));
        assertThat(RequestBase.of(exchange))
                .as("stated outright, without a trailing slash, whatever the request says")
                .isEqualTo("https://artifacts.example.com/registry");
    }

    private static FormatExchange exchange(String scheme, String peer, Map<String, String> headers,
                                           Map<String, String> settings) {
        return new FormatExchange() {
            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String path() {
                return "/npm/left-pad";
            }

            @Override
            public String scheme() {
                return scheme;
            }

            @Override
            public String remoteAddress() {
                return peer;
            }

            @Override
            public String queryParameter(String name) {
                return null;
            }

            @Override
            public String requestHeader(String name) {
                return headers.get(name);
            }

            @Override
            public String setting(String key) {
                return settings.get(key);
            }

            @Override
            public InputStream requestStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public void setResponseHeader(String name, String value) {
            }

            @Override
            public OutputStream respond(int status, long contentLength) {
                return OutputStream.nullOutputStream();
            }
        };
    }
}
