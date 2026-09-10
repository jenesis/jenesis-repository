package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A right may be time-boxed, and not only a secret.
 *
 * <p>Expiry was a field on a credential, so it could end a <em>key</em> and nothing else. An identity is a session
 * rather than a credential, which left "a contractor until the end of March" and "an elevation that lapses on its
 * own" with nothing to attach to - the only way to end a person's access was for somebody to remember. On the
 * grant it works for every holder, because the grant is what every holder holds.
 */
class GrantExpiryTest {

    @TempDir
    Path root;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    @Test
    void a_grant_that_has_not_lapsed_authorizes_and_one_that_has_does_not() throws IOException {
        Authorization.Subject ada = Authorization.Subject.principal("oidc/ada");
        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ,
                Instant.now().plus(Duration.ofHours(1)));
        assertThat(allowed(ada, Authorization.REPOSITORY_READ)).as("still in its window").isTrue();

        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ,
                Instant.now().minus(Duration.ofSeconds(1)));
        assertThat(allowed(ada, Authorization.REPOSITORY_READ))
                .as("past it - and nobody had to remember to remove it").isFalse();
    }

    @Test
    void a_lapsed_grant_is_not_listed_as_a_grant() throws IOException {
        // A surface that showed it would have an operator revoking what already ended, believing it was live.
        Authorization.Subject ada = Authorization.Subject.principal("oidc/ada");
        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ,
                Instant.now().minus(Duration.ofSeconds(1)));
        authorization.setGrant("acme", ada, "main", Authorization.REPOSITORY_WRITE);

        assertThat(authorization.grants("acme", ada))
                .as("the live one, and no trace of the lapsed one or of the bookkeeping behind it")
                .containsExactly(Map.entry("main", Authorization.REPOSITORY_WRITE));
    }

    @Test
    void re_granting_without_an_expiry_clears_the_one_it_had() throws IOException {
        // Otherwise an expiry set once could never be lifted except by revoking and re-granting, and an operator
        // extending someone's access would silently not have.
        Authorization.Subject ada = Authorization.Subject.principal("oidc/ada");
        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ,
                Instant.now().minus(Duration.ofSeconds(1)));
        assertThat(allowed(ada, Authorization.REPOSITORY_READ)).isFalse();

        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ);
        assertThat(allowed(ada, Authorization.REPOSITORY_READ)).as("no expiry means no expiry").isTrue();
    }

    @Test
    void a_group_grant_lapses_for_everyone_who_held_it_through_the_group() throws IOException {
        // The reason expiry belongs on the grant rather than on the holder: one date ends it for the whole team.
        authorization.setGrant("acme", Authorization.Subject.group("contractors"), "*",
                Authorization.REPOSITORY_WRITE, Instant.now().plus(Duration.ofHours(1)));
        authorization.addMember("acme", "contractors", "oidc/ada");
        assertThat(allowed(Authorization.Subject.principal("oidc/ada"), Authorization.REPOSITORY_WRITE)).isTrue();

        authorization.setGrant("acme", Authorization.Subject.group("contractors"), "*",
                Authorization.REPOSITORY_WRITE, Instant.now().minus(Duration.ofSeconds(1)));
        assertThat(allowed(Authorization.Subject.principal("oidc/ada"), Authorization.REPOSITORY_WRITE))
                .as("the whole group's access ended on one date").isFalse();
    }

    @Test
    void an_unreadable_expiry_is_treated_as_lapsed_rather_than_as_absent() throws IOException {
        // Fail closed. The value exists only because somebody time-boxed the grant, so reading a corrupt one as
        // "no expiry" would answer the opposite of what they asked for.
        Authorization.Subject ada = Authorization.Subject.principal("oidc/ada");
        authorization.setGrant("acme", ada, "*", Authorization.REPOSITORY_READ,
                Instant.now().plus(Duration.ofHours(1)));

        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        store.write(".system/auth/acme/principal/oidc%2Fada/grants",
                new ByteArrayInputStream(("*=" + Authorization.REPOSITORY_READ + "\n.expires.*=not-an-instant\n")
                        .getBytes(StandardCharsets.UTF_8)));
        Authorization reread = Authorization.enforcing(store);

        assertThat(reread.authorize("acme", ada, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    private boolean allowed(Authorization.Subject subject, String right) throws IOException {
        return authorization.authorize("acme", subject, null, right) == Authorization.Decision.ALLOWED;
    }
}
