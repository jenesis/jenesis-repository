package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.ServletFormatExchange;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether a write may administer the repository it addresses is the credential's {@code manage:write} on that
 * repository: a key that may publish there - even one holding every repository right - answers no, an operator's key
 * answers yes, an anonymous deployment answers yes as it does every question, and a request no authorization decided
 * administers nothing.
 */
class AdministrationAuthorizationTest {

    private static final String KEYRING = "/repository/acme/packages/keyring";

    @TempDir
    Path root;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    private String key(String scope, String rights) throws IOException {
        String key = Authorization.mint("acme");
        authorization.provision("acme", Authorization.hash(key), "k", null);
        authorization.setGrant("acme", Authorization.hash(key), scope, rights);
        return key;
    }

    private Map<String, Object> authorize(Authorization deciding, String method, String key) {
        Map<String, Object> attributes = new HashMap<>();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(KEYRING);
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        new RepositoryAuthorizationManager(deciding).authorize(() -> null, new RequestAuthorizationContext(request));
        return attributes;
    }

    private static boolean administers(Map<String, Object> attributes) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return new ServletFormatExchange(request, mock(HttpServletResponse.class), KEYRING).administers();
    }

    @Test
    void a_key_that_may_publish_does_not_administer() throws IOException {
        assertThat(administers(authorize(authorization, "POST", key("packages", "repository:*")))).isFalse();
    }

    @Test
    void an_operator_s_key_administers() throws IOException {
        String operator = key("packages", "repository:*,manage:*");

        assertThat(administers(authorize(authorization, "POST", operator))).isTrue();
    }

    @Test
    void the_right_on_another_repository_is_not_this_ones() throws IOException {
        String here = Authorization.mint("acme");
        authorization.provision("acme", Authorization.hash(here), "k", null);
        authorization.setGrant("acme", Authorization.hash(here), "packages", "repository:*");
        authorization.setGrant("acme", Authorization.hash(here), "other", "manage:*");

        assertThat(administers(authorize(authorization, "POST", here))).isFalse();
    }

    @Test
    void an_anonymous_deployment_administers() {
        assertThat(administers(authorize(Authorization.anonymous(), "POST", null))).isTrue();
    }

    @Test
    void a_read_carries_no_question_and_an_undecided_request_administers_nothing() throws IOException {
        assertThat(authorize(authorization, "GET", key("packages", "repository:*,manage:*")))
                .doesNotContainKey(RepositoryAuthorizationManager.ADMINISTERS);
        assertThat(administers(Map.of())).isFalse();
    }
}
