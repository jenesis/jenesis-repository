package build.jenesis.repository.auth.keylogin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.auth.keylogin.FirstRunKey;
import build.jenesis.repository.auth.keylogin.FirstRunWelcome;
import build.jenesis.repository.auth.keylogin.KeyLoginAuthenticationProvider;
import build.jenesis.repository.auth.keylogin.KeyLoginKeys;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.identity.StarterCredential;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one-time key a deployment nobody can sign in to prints at start: minted exactly when nobody can sign in, stored
 * only as a hash, accepted for its hour and only until an administrator exists, and signing in with it yields the
 * starter-credential session the setup guide keys on. Each refusal is asserted beside the acceptance it limits, so a
 * key checked without its clock or without the administrator test fails here rather than in a deployment.
 */
class FirstRunKeyTest {

    @TempDir
    Path root;

    private Documents documents;
    private KeyLoginKeys keys;
    private final AtomicBoolean administered = new AtomicBoolean();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-25T12:00:00Z"));

    @BeforeEach
    void setUp() {
        documents = CacheStorages.documents(root);
        keys = new KeyLoginKeys(documents);
    }

    private FirstRunKey firstRun(boolean adminKeySet) {
        return new FirstRunKey(documents, keys, adminKeySet, administered::get, clock);
    }

    @Test
    void a_deployment_nobody_can_sign_in_to_mints_a_key_that_signs_in_as_the_starter_credential() throws IOException {
        FirstRunKey firstRun = firstRun(false);
        FirstRunKey.Issued issued = firstRun.issueIfNeeded().orElseThrow();

        assertThat(issued.expires()).isEqualTo(clock.instant().plus(FirstRunKey.VALIDITY));
        assertThat(firstRun.accepts(issued.key())).isTrue();

        RecordingAuditTrail audit = new RecordingAuditTrail();
        Authentication session = new KeyLoginAuthenticationProvider(keys, firstRun, "", allow(), 30.0, audit,
                "default", id -> false).authenticate(UsernamePasswordAuthenticationToken.unauthenticated("",
                issued.key()));
        assertThat(roles(session))
                .as("a super-admin marked as the starter credential, which is what sends it to the setup guide")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_SUPERADMIN", StarterCredential.AUTHORITY);
        assertThat(audit.has("login", "admin")).isTrue();
    }

    @Test
    void the_key_is_refused_once_its_hour_is_up() throws IOException {
        FirstRunKey firstRun = firstRun(false);
        String key = firstRun.issueIfNeeded().orElseThrow().key();

        clock.advance(FirstRunKey.VALIDITY.minusSeconds(1));
        assertThat(firstRun.accepts(key)).as("still inside its hour").isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(firstRun.accepts(key)).as("at the end of its hour").isFalse();
    }

    @Test
    void the_key_is_refused_as_soon_as_an_administrator_exists() throws IOException {
        FirstRunKey firstRun = firstRun(false);
        String key = firstRun.issueIfNeeded().orElseThrow().key();
        assertThat(firstRun.accepts(key)).isTrue();

        administered.set(true);
        assertThat(firstRun.accepts(key)).as("the guide's first step grants an administrator, and that ends it")
                .isFalse();
        assertThatThrownBy(() -> new KeyLoginAuthenticationProvider(keys, firstRun, "", allow(), 30.0,
                new RecordingAuditTrail(), "default", id -> false)
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("", key)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void no_key_is_minted_where_somebody_can_already_sign_in_and_what_earlier_starts_left_is_cleared()
            throws IOException {
        String leftover = firstRun(false).issueIfNeeded().orElseThrow().key();

        assertThat(firstRun(true).issueIfNeeded()).as("an admin key in the environment").isEmpty();
        assertThat(firstRun(true).accepts(leftover)).as("the earlier start's key went with it").isFalse();

        String another = firstRun(false).issueIfNeeded().orElseThrow().key();
        administered.set(true);
        assertThat(firstRun(false).issueIfNeeded()).as("a deployment administrator").isEmpty();
        administered.set(false);
        assertThat(firstRun(false).accepts(another)).isFalse();

        keys.issue("keylogin/octocat", "default", "octocat");
        assertThat(firstRun(false).issueIfNeeded()).as("an issued login key").isEmpty();
    }

    @Test
    void only_a_hash_of_the_key_is_stored() throws IOException {
        String key = firstRun(false).issueIfNeeded().orElseThrow().key();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain(key);
            }
        }
    }

    @Test
    void a_key_that_was_never_printed_is_refused() throws IOException {
        FirstRunKey firstRun = firstRun(false);
        firstRun.issueIfNeeded().orElseThrow();
        assertThat(firstRun.accepts("jfr_notakeythisdeploymentprinted0000000")).isFalse();
        assertThat(firstRun.accepts("")).isFalse();
        assertThat(firstRun.accepts(null)).isFalse();
    }

    @Test
    void the_welcome_names_the_key_where_to_use_it_and_until_when() {
        String message = FirstRunWelcome.message(
                new FirstRunKey.Issued("jfr_example", Instant.parse("2026-09-25T13:00:00Z")), "8080");
        assertThat(message).contains("WELCOME TO JENESIS REPOSITORY", "jfr_example",
                "http://localhost:8080/ui/", "Sign in with a key", "2026-09-25 13:00 UTC");
    }

    private static Set<String> roles(Authentication authentication) {
        Set<String> roles = new HashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority());
        }
        return roles;
    }

    private static RateLimiter allow() {
        return (key, permitsPerMinute) -> true;
    }

    /** A clock a test moves by hand, so an hour passes without waiting one. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
