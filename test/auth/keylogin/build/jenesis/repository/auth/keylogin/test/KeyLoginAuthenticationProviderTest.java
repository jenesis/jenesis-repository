package build.jenesis.repository.auth.keylogin.test;

import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.identity.StarterCredential;
import build.jenesis.repository.auth.keylogin.KeyLoginAuthenticationProvider;
import build.jenesis.repository.auth.keylogin.KeyLoginKeys;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.RateLimiter;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key sign-in decision: the env bootstrap admin key signs in as a full super-admin; an issued key signs in at
 * {@code ROLE_USER} (its tenant role resolved elsewhere) unless the principal is also an env super-admin; an unknown,
 * blank or wrong key is refused; the rate limiter is consulted first and a refusal blocks even a valid key; and every
 * outcome is audited. Keys are never compared in the clear - the provider holds only the admin key's hash.
 */
public class KeyLoginAuthenticationProviderTest {

    @TempDir
    Path root;

    private static final String ADMIN_KEY = "jkl_bootstrap-admin-key-value";

    private KeyLoginKeys keys;
    private RecordingAuditTrail audit;

    @BeforeEach
    void setUp() {
        keys = new KeyLoginKeys(CacheStorages.documents(root));
        audit = new RecordingAuditTrail();
    }

    private KeyLoginAuthenticationProvider provider(RateLimiter limiter, Predicate<String> superadmin) {
        return new KeyLoginAuthenticationProvider(keys, ADMIN_KEY, limiter, 30.0, audit, "default", superadmin);
    }

    private static Set<String> roles(Authentication authentication) {
        Set<String> roles = new HashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority());
        }
        return roles;
    }

    private static Authentication attempt(String key) {
        return UsernamePasswordAuthenticationToken.unauthenticated("", key);
    }

    @Test
    void envAdminKeySignsInAsSuperadmin() {
        Authentication result = provider(allow(), id -> false).authenticate(attempt(ADMIN_KEY));
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo("admin");
        assertThat(roles(result)).as("super-admin, and marked as the starter credential the setup guide keys on")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_SUPERADMIN", StarterCredential.AUTHORITY);
        assertThat(audit.has("login", "admin")).isTrue();
    }

    @Test
    void issuedKeySignsInAtRoleUserOnly() throws IOException {
        KeyLoginKeys.Issued issued = keys.issue("keylogin/octocat", "acme", "octocat");
        Authentication result = provider(allow(), id -> false).authenticate(attempt(issued.key()));
        assertThat(result.getName()).isEqualTo("keylogin/octocat");
        assertThat(roles(result)).containsExactly("ROLE_USER");
        assertThat(audit.has("login", "keylogin/octocat")).isTrue();
    }

    @Test
    void issuedKeyForAnEnvSuperadminAlsoGetsSuperadmin() throws IOException {
        KeyLoginKeys.Issued issued = keys.issue("keylogin/boss", "acme", "boss");
        Authentication result = provider(allow(), "keylogin/boss"::equals).authenticate(attempt(issued.key()));
        assertThat(roles(result)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_SUPERADMIN");
    }

    @Test
    void unknownKeyIsRefusedAndAudited() {
        assertThatThrownBy(() -> provider(allow(), id -> false).authenticate(attempt("jkl_nope")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(audit.has("login.failed", "anonymous")).isTrue();
    }

    @Test
    void aWrongAdminKeyDoesNotSignIn() {
        assertThatThrownBy(() -> provider(allow(), id -> false).authenticate(attempt("jkl_bootstrap-admin-key-valuE")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void anExhaustedRateLimitBlocksEvenTheAdminKey() {
        assertThatThrownBy(() -> provider(deny(), id -> false).authenticate(attempt(ADMIN_KEY)))
                .isInstanceOf(AuthenticationServiceException.class);
        assertThat(audit.has("login.throttled", "anonymous")).isTrue();
    }

    @Test
    void theRateLimiterIsKeyedByClientAndCeiling() {
        List<String> seen = new ArrayList<>();
        RateLimiter capturing = (key, permits) -> {
            seen.add(key + "@" + permits);
            return true;
        };
        provider(capturing, id -> false).authenticate(attempt(ADMIN_KEY));
        assertThat(seen).containsExactly("keylogin:unknown@30.0");
    }

    private static RateLimiter allow() {
        return (key, permits) -> true;
    }

    private static RateLimiter deny() {
        return (key, permits) -> false;
    }
}
