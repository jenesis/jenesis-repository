package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A person holds rights the same way a key does.
 *
 * <p>Until this, {@code authorize} resolved a credential and nothing else, so the four subject kinds were a
 * storage layout with one live member. A principal is now the second: the same {@code <surface>:<verb>}
 * vocabulary, the same scope grammar, matched by the same code - the difference is only how the subject comes to
 * be held, a key by presenting a secret and a person by having signed in. That is what makes "one vocabulary,
 * several holders" a fact about the code rather than an intention.
 *
 * <p>What is asserted here is the pair that has to hold together: a granted principal is <em>allowed</em>, and a
 * subject kind {@code authorize} does not resolve is <em>refused a grant</em> rather than quietly given one. A
 * security surface may answer "no"; it may not answer "yes" and mean nothing.
 */
class PrincipalGrantTest {

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
    void a_principal_holds_the_rights_it_was_granted() throws IOException {
        Authorization.Subject octocat = Authorization.Subject.principal("github/octocat");
        authorization.setGrant("acme", octocat, "*", Authorization.REPOSITORY_READ);

        assertThat(authorization.authorize("acme", octocat, null, Authorization.REPOSITORY_READ))
                .as("the right it was granted").isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme", octocat, null, Authorization.REPOSITORY_WRITE))
                .as("and only that one").isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_principal_with_no_grant_holds_nothing() throws IOException {
        assertThat(authorization.authorize("acme",
                Authorization.Subject.principal("github/stranger"), null, Authorization.REPOSITORY_READ))
                .as("a person nobody granted anything is refused, not defaulted")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_grant_is_scoped_to_its_tenant_and_to_its_repository() throws IOException {
        Authorization.Subject octocat = Authorization.Subject.principal("github/octocat");
        authorization.setGrant("acme", octocat, "releases", Authorization.REPOSITORY_WRITE);

        assertThat(authorization.authorize("acme", octocat, "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme", octocat, "snapshots", Authorization.REPOSITORY_WRITE))
                .as("another repository in the same tenant is not covered")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
        assertThat(authorization.authorize("other", octocat, "releases", Authorization.REPOSITORY_WRITE))
                .as("and the same id in another tenant is another subject entirely")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void the_provider_qualification_is_part_of_who_the_person_is() throws IOException {
        // github/octocat and oidc/octocat are two people. The id carries its provider, which is why a subject id
        // may hold a slash at all - and why the KEY has to encode it rather than let it become a path.
        Authorization.Subject fromGithub = Authorization.Subject.principal("github/octocat");
        Authorization.Subject fromOidc = Authorization.Subject.principal("oidc/octocat");
        authorization.setGrant("acme", fromGithub, "*", Authorization.REPOSITORY_WRITE);

        assertThat(authorization.authorize("acme", fromGithub, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme", fromOidc, null, Authorization.REPOSITORY_WRITE))
                .as("the same local name from another provider is a different subject")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void two_ids_that_differ_only_by_an_encoded_slash_do_not_share_a_key() throws IOException {
        // The escaping rule, stated as the property it exists for: without escaping the percent first,
        // "a%2Fb" and "a/b" would compose the same key - one person's grants answering for another's.
        Authorization.Subject slashed = Authorization.Subject.principal("github/a/b");
        Authorization.Subject literal = Authorization.Subject.principal("github/a%2Fb");
        authorization.setGrant("acme", slashed, "*", Authorization.REPOSITORY_WRITE);

        assertThat(authorization.authorize("acme", slashed, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme", literal, null, Authorization.REPOSITORY_WRITE))
                .as("an id that merely looks like the other's encoding is not the other")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_deployment_wide_grant_is_held_in_every_tenant_including_ones_created_later() throws IOException {
        // The operator who administers the deployment itself. Expressing this as a grant per tenant would be a set
        // to maintain as tenants come and go: one missed and they are locked out of the newest tenant, one stale
        // and a removed operator keeps a tenant nobody thought to check. It is one row, read beside the tenant's.
        Authorization.Subject root = Authorization.Subject.principal("oidc/root");
        authorization.setGrant(Authorization.DEPLOYMENT, root, "*", Authorization.MANAGE_WRITE);

        assertThat(authorization.authorize("acme", root, null, Authorization.MANAGE_WRITE))
                .as("held in a tenant that existed when it was granted").isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("a-tenant-nobody-had-created-yet", root, null,
                Authorization.MANAGE_WRITE))
                .as("and in one that did not").isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme", root, null, Authorization.REPOSITORY_WRITE))
                .as("but only the rights it actually grants").isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_tenant_grant_does_not_leak_into_another_tenant_through_the_deployment_row() throws IOException {
        // The property that makes the second read safe: only the deployment row is consulted beside the tenant's,
        // never another tenant's, so the fan-out is two point reads rather than a walk - and a grant in acme is
        // still exactly a grant in acme.
        Authorization.Subject octocat = Authorization.Subject.principal("github/octocat");
        authorization.setGrant("acme", octocat, "*", Authorization.MANAGE_WRITE);

        assertThat(authorization.authorize("acme", octocat, null, Authorization.MANAGE_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("globex", octocat, null, Authorization.MANAGE_WRITE))
                .as("a tenant grant is not a deployment grant").isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_traversal_segment_is_refused_wherever_it_appears() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Authorization.Subject.principal("github/../admin"))
                .withMessageContaining("traversal");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Authorization.Subject.principal("github//octocat"))
                .withMessageContaining("empty");
    }

    @Test
    void a_credential_id_still_may_not_carry_a_slash() {
        // The old rule, kept where it was always right: a hash is one segment, and a slash in one would be a
        // subject grafted into another's subtree rather than a qualification of who it is.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Authorization.Subject.credential("abc/def"))
                .withMessageContaining("may not contain");
    }

    @Test
    void granting_to_a_kind_authorize_cannot_resolve_is_refused() {
        // The rule this whole change is ordered by: a kind becomes writable in the change that makes it
        // enforceable. A group's rights arrive with membership resolution and the keyless caller's from
        // configuration, so a grant to either would read as access and confer none.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorization.setGrant("acme",
                        Authorization.Subject.group("developers"), "*", Authorization.REPOSITORY_READ))
                .withMessageContaining("confer");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorization.setGrant("acme",
                        Authorization.Subject.ANONYMOUS, "*", Authorization.REPOSITORY_READ))
                .withMessageContaining("confer");
    }
}
