package build.jenesis.repository.test;

import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import module org.junit.jupiter.api;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where a request may carry its key. The native {@code Jenesis-Repository-Key} header is one option; the standard
 * {@code Authorization} header is the other, because that is all most clients can send - the Jenesis build tool
 * forwards its {@code jenesis.*.token} verbatim as {@code Authorization}, and {@code docker login} can only produce
 * {@code Authorization: Basic}. A key lifted out of {@code Authorization} must be well-formed, so a foreign
 * credential in that header never becomes a principal.
 */
class PresentedKeyTest {

    @TempDir
    Path root;

    private static final String KEY = Authorization.mint("acme");

    @Test
    void the_native_header_wins_and_is_returned_as_presented() {
        assertThat(PresentedKey.from(KEY, "Bearer " + Authorization.mint("other"))).isEqualTo(KEY);
        // A malformed native header is still the presented key, so it is rejected downstream as a bad key rather
        // than silently turning the request anonymous.
        assertThat(PresentedKey.from("not-a-key", null)).isEqualTo("not-a-key");
    }

    @Test
    void a_bearer_token_or_a_bare_key_in_authorization_is_the_key() {
        assertThat(PresentedKey.from(null, "Bearer " + KEY)).isEqualTo(KEY);
        assertThat(PresentedKey.from(null, "bearer " + KEY)).as("the scheme is case-insensitive").isEqualTo(KEY);
        assertThat(PresentedKey.from("", KEY)).as("the build tool sends the token verbatim, scheme-less").isEqualTo(KEY);
    }

    @Test
    void a_basic_credential_whose_password_is_a_key_is_the_key() {
        String basic = Base64.getEncoder().encodeToString(("anyone:" + KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(PresentedKey.from(null, "Basic " + basic)).as("docker login -u anyone -p jenk_…").isEqualTo(KEY);
    }

    @Test
    void anything_else_in_authorization_is_no_key_at_all() {
        assertThat(PresentedKey.from(null, "Bearer eyJhbGciOiJSUzI1NiJ9.something")).isNull();
        assertThat(PresentedKey.from(null, "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8)))).isNull();
        assertThat(PresentedKey.from(null, "Basic not*base64")).isNull();
        assertThat(PresentedKey.from(null, "Bearer " + KEY.substring(0, KEY.length() - 1)))
                .as("a truncated key fails its checksum").isNull();
        assertThat(PresentedKey.from(null, null)).isNull();
    }

    @Test
    void a_key_presented_as_a_bearer_token_authorizes_exactly_like_the_native_header() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Authorization authorization = Authorization.enforcing(store);
        authorization.provision("acme", Authorization.hash(KEY), "k", null);
        authorization.grant(KEY, "*", Authorization.REPOSITORY_READ);
        RepositoryRouting.Route route = new RepositoryRouting.Route("acme", "default", store, "maven/org/x/y/1/y-1.jar");
        RepositoryAuthorizationManager manager = new RepositoryAuthorizationManager(authorization, request -> route);

        Map<String, Object> bearer = new HashMap<>();
        assertThat(manager.authorize(() -> null, new RequestAuthorizationContext(request(null, "Bearer " + KEY, bearer)))
                .isGranted()).as("Authorization: Bearer jenk_… reads the artifact").isTrue();
        assertThat(bearer.get("jenreg.decision")).isEqualTo(Authorization.Decision.ALLOWED);

        Map<String, Object> foreign = new HashMap<>();
        assertThat(manager.authorize(() -> null, new RequestAuthorizationContext(request(null, "Bearer not-ours", foreign)))
                .isGranted()).as("a foreign bearer token is a keyless request, which an enforcing server refuses").isFalse();
    }

    @Test
    void the_user_name_of_a_basic_credential_is_readable_separately() {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString(("demo:" + KEY).getBytes(StandardCharsets.UTF_8));

        assertThat(PresentedKey.from(null, basic))
                .as("the password is the key, as it always was")
                .isEqualTo(KEY);
        assertThat(PresentedKey.user(basic))
                .as("and the user name is now readable, because Basic is structurally two slots and a client "
                        + "configurable with nothing else has no other way to send a second value - the cache "
                        + "reads it as the project a Gradle build stores under")
                .isEqualTo("demo");

        assertThat(PresentedKey.user("Bearer " + KEY))
                .as("a bearer token carries no user name")
                .isNull();
        assertThat(PresentedKey.user((String) null)).as("and neither does an absent header").isNull();
        assertThat(PresentedKey.user("Basic ~not-base64~"))
                .as("a malformed credential is no user name rather than an exception")
                .isNull();
        assertThat(PresentedKey.user("Basic " + Base64.getEncoder()
                .encodeToString("nocolon".getBytes(StandardCharsets.UTF_8))))
                .as("RFC 7617 makes the colon mandatory, so a credential without one is malformed")
                .isNull();

        String anonymous = "Basic " + Base64.getEncoder()
                .encodeToString((":" + KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(PresentedKey.user(anonymous))
                .as("an empty user name is returned as sent - nothing here decides what it means, and the "
                        + "caller that gave the slot a meaning is the one that rejects it")
                .isEmpty();
        assertThat(PresentedKey.from(null, anonymous))
                .as("and it does not disturb the key")
                .isEqualTo(KEY);
    }

    private static HttpServletRequest request(String key, String authorization, Map<String, Object> attributes) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/repository/maven/org/x/y/1/y-1.jar");
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        return request;
    }
}
