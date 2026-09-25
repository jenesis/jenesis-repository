package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryAuthorizationManager.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read-only hardening verdict API is a deployment-management surface, so the security chain gates it against the
 * whole deployment ({@code *}) - a GET needs {@code manage:read} before the controller is reached (§10: a caller
 * lacking the write role still sees the read). This pins the classification so the endpoint can never be reached by a
 * caller holding only a per-repository right, and is never accidentally opened wider than the sibling {@code /api/}
 * management reads.
 */
public class HardeningApiScopeTest {

    @Test
    public void the_hardening_verdict_api_is_a_deployment_management_read() {
        // Classified against the whole deployment with manage=true; the read/write split is then the HTTP method
        // (a GET -> manage:read), exactly as the sibling /api/quarantine review read is gated.
        assertThat(RepositoryAuthorizationManager.classify("/api/hardening/verdict"))
                .isEqualTo(new Target("*", null, true));
    }
}
