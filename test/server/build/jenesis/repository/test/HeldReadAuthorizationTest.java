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
 * Whether a read may see what a repository holds for review is the credential's {@code quarantine:read} on that
 * repository, and nothing else's: an artifact read carries the question to the format through the exchange, a key
 * that only reads - even one holding every repository right - answers no, and a write, an anonymous deployment or a
 * request no authorization decided carries nothing at all.
 */
class HeldReadAuthorizationTest {

    private static final String MANIFEST = "/v2/acme/images/team/app/manifests/sha256:" + "a".repeat(64);

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

    private Map<String, Object> authorize(String method, String key) {
        Map<String, Object> attributes = new HashMap<>();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(MANIFEST);
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        new RepositoryAuthorizationManager(authorization).authorize(() -> null, new RequestAuthorizationContext(request));
        return attributes;
    }

    private static boolean readsHeld(Map<String, Object> attributes) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return new ServletFormatExchange(request, mock(HttpServletResponse.class), MANIFEST).readsHeld();
    }

    @Test
    void a_key_carrying_quarantine_read_reads_held_content() throws IOException {
        String scanner = key("images", Authorization.REPOSITORY_READ + "," + Authorization.QUARANTINE_READ);

        assertThat(readsHeld(authorize("GET", scanner))).isTrue();
    }

    @Test
    void a_key_that_only_reads_does_not() throws IOException {
        assertThat(readsHeld(authorize("GET", key("images", Authorization.REPOSITORY_READ)))).isFalse();
    }

    @Test
    void every_repository_right_is_not_the_right_to_read_what_is_held() throws IOException {
        assertThat(readsHeld(authorize("GET", key("images", "repository:*")))).isFalse();
    }

    @Test
    void the_right_on_another_repository_is_not_this_ones() throws IOException {
        String elsewhere = key("other", Authorization.REPOSITORY_READ + "," + Authorization.QUARANTINE_READ);
        String here = Authorization.mint("acme");
        authorization.provision("acme", Authorization.hash(here), "k", null);
        authorization.setGrant("acme", Authorization.hash(here), "images", Authorization.REPOSITORY_READ);
        authorization.setGrant("acme", Authorization.hash(here), "other", Authorization.QUARANTINE_READ);

        assertThat(readsHeld(authorize("GET", here))).isFalse();
        assertThat(authorize("GET", elsewhere)).doesNotContainKey(RepositoryAuthorizationManager.READS_HELD);
    }

    @Test
    void a_write_carries_no_question() throws IOException {
        String scanner = key("images", "repository:read,repository:write," + Authorization.QUARANTINE_READ);

        assertThat(authorize("PUT", scanner)).doesNotContainKey(RepositoryAuthorizationManager.READS_HELD);
    }

    @Test
    void an_undecided_request_reads_nothing_held() {
        assertThat(readsHeld(Map.of())).isFalse();
    }
}
