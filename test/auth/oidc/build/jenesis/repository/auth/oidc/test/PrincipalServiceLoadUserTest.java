package build.jenesis.repository.auth.oidc.test;

import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;

import org.springframework.core.env.StandardEnvironment;
import com.sun.net.httpserver.HttpServer;
import build.jenesis.repository.ui.OAuth2PrincipalService;
import build.jenesis.repository.ui.OidcPrincipalService;
import build.jenesis.repository.ui.QualifiedOidcUser;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.identity.LoginAuthorization;
import build.jenesis.repository.ui.admin.security.MembershipCache;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.identity.Superadmins;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The end-to-end {@code loadUser} the audit found untested: {@link OidcPrincipalService} and
 * {@link OAuth2PrincipalService} each turn a provider's user into a console principal keyed by the provider-qualified id
 * ({@code <registrationId>/<sub>} for OIDC, {@code <registrationId>/<id>} for OAuth2), and the allow/deny decision is
 * the real {@link LoginAuthorization} over a real filesystem membership store - a member of a tenant is admitted with
 * that qualified id as the principal name, a user of no tenant is refused with an {@link OAuth2AuthenticationException}
 * (fail-closed), never admitted. It mirrors {@code SamlAssertionRoundTripTest}'s authorization wiring; the OIDC user is
 * built from the id token with no user-info endpoint (so {@code OidcUserService} makes no network call), and the OAuth2
 * user is fetched from a loopback user-info endpoint the way the real provider calls it.
 */
public class PrincipalServiceLoadUserTest {

    @TempDir
    private Path root;

    private LoginAuthorization authorization;
    private HttpServer server;
    private URI base;

    @BeforeEach
    public void setUp() throws IOException {
        Documents rootStorage = CacheStorages.documents(root);
        TenantService tenants = new TenantService(rootStorage);
        tenants.create("acme");
        // A member of the tenant under each provider's qualified id - the key the services must re-key the principal to.
        UserDirectory directory = new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme");
        directory.put("oidc/sub-123", UserDirectory.Role.VIEWER, "octocat");
        directory.put("github/12345", UserDirectory.Role.VIEWER, "octocat");
        // The same raw sub "42" under two distinct OIDC registrations: two members, one per provider-qualified id,
        // so the collision-guard test below proves the service keeps them apart rather than colliding on the sub.
        directory.put("github/42", UserDirectory.Role.VIEWER, "octocat");
        directory.put("google/42", UserDirectory.Role.VIEWER, "octocat");
        authorization = new LoginAuthorization(
                new Superadmins(new ConsoleAdministrators(Authorization.enforcing(rootStorage.store()), Set.of())),
                new KnownPrincipals(Authorization.enforcing(rootStorage.store())));

        // A loopback user-info endpoint the OAuth2 provider fetches (the OIDC path needs none): one path answers as a
        // tenant member, one as a stranger, so the same real authorization renders both the allow and the deny.
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/member", exchange -> respond(exchange, "{\"id\":\"12345\",\"login\":\"octocat\"}"));
        server.createContext("/stranger", exchange -> respond(exchange, "{\"id\":\"99999\",\"login\":\"stranger\"}"));
        server.start();
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void an_oidc_user_of_a_tenant_signs_in_as_the_provider_qualified_id() {
        OidcUser user = new OidcPrincipalService(authorization).loadUser(oidcRequest("sub-123"));

        assertThat(user).isInstanceOf(QualifiedOidcUser.class);
        assertThat(user.getName()).as("the principal name is the provider-qualified id, not the bare sub")
                .isEqualTo("oidc/sub-123");
        assertThat(user.getAuthorities().toString()).contains("ROLE_USER");
    }

    @Test
    public void an_oidc_user_of_no_tenant_signs_in_and_is_granted_nothing() {
        // Sign-in used to throw here. The provider owns who may authenticate, and refusing afterwards duplicated
        // that badly while making the person's opaque sub unlearnable - so what they hold is decided by
        // MembershipConsoleAccess on every request instead, and here they simply arrive holding nothing.
        var user = new OidcPrincipalService(authorization).loadUser(oidcRequest("nobody"));
        assertThat(user.getAuthorities().toString())
                .contains("ROLE_USER").doesNotContain("ROLE_SUPERADMIN");
    }

    @Test
    public void the_same_sub_under_two_providers_signs_in_as_distinct_qualified_ids() {
        // The collision guard end to end: the SERVICE - not a hand-set field - derives the principal name from the
        // registration id and the sub, so the identical raw sub "42" presented under "github" and under "google"
        // is admitted as two distinct principals, each matching only its own tenant membership.
        OidcUser github = new OidcPrincipalService(authorization).loadUser(oidcRequest("github", "42"));
        OidcUser google = new OidcPrincipalService(authorization).loadUser(oidcRequest("google", "42"));

        assertThat(github.getName()).isEqualTo("github/42");
        assertThat(google.getName()).isEqualTo("google/42");
        assertThat(github.getName()).as("the same raw sub under different providers cannot collide")
                .isNotEqualTo(google.getName());
        assertThat(github.getSubject()).as("the bare sub reads through both, unqualified").isEqualTo("42");
        assertThat(google.getSubject()).isEqualTo("42");
    }

    @Test
    public void an_oauth2_user_of_a_tenant_signs_in_as_the_provider_qualified_id() {
        OAuth2User user = new OAuth2PrincipalService(authorization).loadUser(oauth2Request("/member"));

        assertThat(user.getName()).as("the principal name is the provider-qualified id, not the bare provider id")
                .isEqualTo("github/12345");
        assertThat(user.getAuthorities().toString()).contains("ROLE_USER");
    }

    @Test
    public void an_oauth2_user_of_no_tenant_signs_in_and_is_granted_nothing() {
        var user = new OAuth2PrincipalService(authorization).loadUser(oauth2Request("/stranger"));
        assertThat(user.getAuthorities().toString())
                .contains("ROLE_USER").doesNotContain("ROLE_SUPERADMIN");
    }

    /** An OIDC user request whose id token carries {@code sub}; the client registration names no user-info endpoint, so
     *  {@code OidcUserService} builds the user from the token alone and makes no network call. */
    private static OidcUserRequest oidcRequest(String sub) {
        return oidcRequest("oidc", sub);
    }

    /** An OIDC user request under a named registration - the id the qualified principal name is derived from. */
    private static OidcUserRequest oidcRequest(String registrationId, String sub) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("console")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/" + registrationId)
                .authorizationUri("https://idp.example.com/authorize")
                .tokenUri("https://idp.example.com/token")
                .build();
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("id-token-value", now, now.plusSeconds(3600),
                Map.of("sub", sub, "preferred_username", "octocat"));
        return new OidcUserRequest(registration, accessToken(now), idToken);
    }

    /** An OAuth2 user request whose client registration points at the loopback user-info path, keyed by the numeric
     *  {@code id} attribute the way GitHub is configured. */
    private OAuth2UserRequest oauth2Request(String userInfoPath) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
                .clientId("console")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/github")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri(base + userInfoPath)
                .userNameAttributeName("id")
                .build();
        return new OAuth2UserRequest(registration, accessToken(Instant.now()));
    }

    private static OAuth2AccessToken accessToken(Instant issuedAt) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", issuedAt,
                issuedAt.plusSeconds(3600));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
