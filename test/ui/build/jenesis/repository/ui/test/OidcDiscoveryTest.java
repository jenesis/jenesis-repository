package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.OidcDiscovery;

import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider discovery, against a server that serves the documents rather than against a mock.
 *
 * <p>The interesting test is the third one. Discovery replaced {@code ClientRegistrations.fromIssuerLocation},
 * which was the only reason the Nimbus OIDC SDK was on the graph, and the one thing a reimplementation can drop
 * without anybody noticing is the check that the document's {@code issuer} is the issuer that was asked for. It is
 * required by OpenID Connect Discovery and RFC 8414, and it is the whole defence against a discovery endpoint
 * handing back somebody else's authorisation server - after which every token this deployment accepts was minted
 * by whoever that is. A dependency cleanup that silently removed it would look like a success.
 */
class OidcDiscoveryTest {

    private HttpServer server;

    private String issuer;

    private final Map<String, String> documents = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = documents.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_provider_is_discovered_from_its_openid_configuration() {
        documents.put("/.well-known/openid-configuration", document(issuer));

        ClientRegistration registration = OidcDiscovery.fromIssuerLocation(issuer)
                .registrationId("oidc")
                .clientId("client")
                .clientSecret("secret")
                .build();

        assertThat(registration.getProviderDetails().getAuthorizationUri()).isEqualTo(issuer + "/authorize");
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo(issuer + "/token");
        assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo(issuer + "/jwks");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(issuer);
        assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo(issuer + "/userinfo");
        assertThat(registration.getScopes())
                .as("openid is the floor: it is what makes this an OIDC login rather than a plain OAuth2 one, and "
                        + "what a spec-compliant provider requires before UserInfo will answer")
                .contains("openid");
        assertThat(registration.getClientAuthenticationMethod())
                .as("the document advertises basic, and it is also the specification's default")
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registration.getProviderDetails().getConfigurationMetadata())
                .as("the whole document is kept, so a field this class does not model is still reachable")
                .containsKey("end_session_endpoint");
    }

    @Test
    void an_authorization_server_that_serves_only_the_rfc_8414_document_is_still_discovered() {
        documents.put("/.well-known/oauth-authorization-server", document(issuer));

        ClientRegistration registration = OidcDiscovery.fromIssuerLocation(issuer)
                .registrationId("oidc").clientId("client").clientSecret("secret").build();

        assertThat(registration.getProviderDetails().getTokenUri())
                .as("the OIDC location 404s, so the RFC 8414 one is tried - providers differ on which they serve")
                .isEqualTo(issuer + "/token");
    }

    @Test
    void a_document_naming_a_different_issuer_is_refused() {
        documents.put("/.well-known/openid-configuration", document("https://attacker.example"));

        assertThatThrownBy(() -> OidcDiscovery.fromIssuerLocation(issuer))
                .as("the document must belong to the issuer that was asked for. Accepting one that names another "
                        + "provider means every token this deployment then trusts was minted by that provider - "
                        + "which is the attack both specifications require this check to stop")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attacker.example");
    }

    @Test
    void a_document_without_a_token_endpoint_is_refused_by_name() {
        documents.put("/.well-known/openid-configuration",
                "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\"}");

        assertThatThrownBy(() -> OidcDiscovery.fromIssuerLocation(issuer))
                .as("an incomplete document says which field is missing, because the alternative is a login that "
                        + "fails later with nothing pointing at the provider's configuration")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token_endpoint");
    }

    @Test
    void an_issuer_that_serves_nothing_names_what_was_tried() {
        assertThatThrownBy(() -> OidcDiscovery.fromIssuerLocation(issuer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".well-known/openid-configuration")
                .hasMessageContaining(".well-known/oauth-authorization-server");
    }

    private static String document(String declaredIssuer) {
        return """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%2$s/authorize",
                  "token_endpoint": "%2$s/token",
                  "jwks_uri": "%2$s/jwks",
                  "userinfo_endpoint": "%2$s/userinfo",
                  "end_session_endpoint": "%2$s/logout",
                  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
                  "response_types_supported": ["code"]
                }
                """.formatted(declaredIssuer, declaredIssuer.equals("https://attacker.example")
                        ? "https://attacker.example" : declaredIssuer);
    }
}
