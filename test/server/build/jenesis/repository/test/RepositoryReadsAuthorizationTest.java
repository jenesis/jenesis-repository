package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which other repositories an answer may name versions of is the credential's {@code repository:read} on each: an
 * allowed read carries the question, answered per repository, an anonymous deployment answers yes to every one, and a
 * refused read carries nothing.
 */
class RepositoryReadsAuthorizationTest {

    @TempDir
    Path root;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    private String key(String... scopes) throws IOException {
        String key = Authorization.mint("acme");
        authorization.provision("acme", Authorization.hash(key), "k", null);
        for (String scope : scopes) {
            authorization.setGrant("acme", Authorization.hash(key), scope, Authorization.REPOSITORY_READ);
        }
        return key;
    }

    private Map<String, Object> authorize(Authorization deciding, String key) {
        Map<String, Object> attributes = new HashMap<>();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/repository/relied-on");
        when(request.getQueryString()).thenReturn("repo=proxy&ecosystem=Maven&coordinate=org.dep:lib&version=1.0");
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        new RepositoryAuthorizationManager(deciding).authorize(() -> null, new RequestAuthorizationContext(request));
        return attributes;
    }

    @SuppressWarnings("unchecked")
    private static Predicate<String> reads(Map<String, Object> attributes) {
        return (Predicate<String>) attributes.get(RepositoryAuthorizationManager.READS_REPOSITORY);
    }

    @Test
    void an_allowed_read_names_only_the_repositories_its_key_reads() throws IOException {
        Predicate<String> reads = reads(authorize(authorization, key("proxy", "releases")));

        assertThat(reads.test("releases")).isTrue();
        assertThat(reads.test("proxy")).isTrue();
        assertThat(reads.test("private")).as("a repository the key holds no right on").isFalse();
    }

    @Test
    void an_anonymous_deployment_names_every_repository() {
        assertThat(reads(authorize(Authorization.anonymous(), null)).test("private")).isTrue();
    }

    @Test
    void a_refused_read_carries_nothing() throws IOException {
        assertThat(authorize(authorization, key("releases")))
                .doesNotContainKey(RepositoryAuthorizationManager.READS_REPOSITORY);
    }
}
