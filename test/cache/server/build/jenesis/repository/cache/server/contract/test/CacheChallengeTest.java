package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.Cache;
import build.jenesis.repository.cache.server.CacheController;
import build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a cache request that presents no key is told: a {@code 401} naming each scheme a key is accepted in, so a
 * client that waits for a challenge, or picks its scheme from one, knows how to present the key it holds.
 */
class CacheChallengeTest {

    private static final String KEY = Authorization.mint("acme");

    @TempDir
    private Path root;

    @Test
    void a_request_without_a_key_is_challenged_for_basic_and_for_bearer() throws IOException {
        Refusal refusal = get(null);
        assertThat(refusal.status()).isEqualTo(401);
        assertThat(refusal.challenges())
                .as("Basic first, for the clients that answer the first challenge they read")
                .containsExactly("Basic realm=\"Jenesis Cache\"", "Bearer realm=\"Jenesis Cache\"");
    }

    @Test
    void a_request_that_presents_its_key_is_not_challenged() throws IOException {
        Refusal refusal = get("Bearer " + KEY);
        assertThat(refusal.status()).as("a miss for a key the node accepts").isEqualTo(404);
        assertThat(refusal.challenges()).isEmpty();
    }

    private Refusal get(String authorization) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        Authorization.enforcing(store).setGrant("acme", Authorization.hash(KEY), "*", "cache:read,cache:write");
        CacheController node = new CacheController(new Cache(
                new DelegatingCacheStorage(store.scope(Scopes.SYSTEM).scope(Scopes.CACHE)),
                Authorization.enforcing(store), 1L << 31, 256, null, 0, 0, "default", false, null, "default",
                new SimpleMeterRegistry()));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/build/acme/0123456789abcdef0123456789abcdef/00000000000000000000000000000001");
        when(request.getContextPath()).thenReturn("");
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getHeader("Jenesis-Cache-Project")).thenReturn("demo");
        int[] status = {200};
        List<String> challenges = new ArrayList<>();
        HttpServletResponse response = mock(HttpServletResponse.class);
        doAnswer(call -> status[0] = call.getArgument(0)).when(response).setStatus(anyInt());
        doAnswer(call -> {
            challenges.clear();
            challenges.add(call.getArgument(1));
            return null;
        }).when(response).setHeader(eq("WWW-Authenticate"), anyString());
        doAnswer(call -> challenges.add(call.getArgument(1))).when(response).addHeader(eq("WWW-Authenticate"), anyString());
        node.dispatch(request, response);
        return new Refusal(status[0], List.copyOf(challenges));
    }

    private record Refusal(int status, List<String> challenges) {
    }
}
