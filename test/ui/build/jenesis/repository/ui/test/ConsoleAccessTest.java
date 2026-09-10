package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.ConsoleAccessRule;
import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.GrantedConsoleAccess;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's floor: sign-in succeeds for anyone the identity provider authenticates, so what a signed-in
 * principal may <em>see</em> is decided here, against grants.
 *
 * <p>This is the part of that decision that has to be right for the open sign-in to be safe rather than merely
 * permissive - before it, {@code anyRequest().authenticated()} meant a signed-in stranger read every screen. The
 * claims here are the policy's: a principal that holds nothing is refused, one that holds something is not, and an
 * anonymous token is neither. That the refusal a stranger gets is a <em>different event</em> from the one a
 * colleague gets at a screen that is not theirs is proved over a real filter chain in the downstream console's
 * {@code ConsoleSecurityFilterTest}, which is where a servlet response exists to be asserted on.
 */
class ConsoleAccessTest {

    @TempDir
    Path root;

    @Test
    void a_principal_that_holds_nothing_is_refused_and_one_that_holds_something_is_not() throws IOException {
        ArtifactStore store = store();
        ConsoleAccess access = access(store, "");
        assertThat(access.holdsAnything("oidc/stranger"))
                .as("being signed in is not holding something").isFalse();

        Authorization.enforcing(store).setGrant("acme", Authorization.Subject.principal("oidc/reader"),
                "*", Authorization.REPOSITORY_READ);
        assertThat(access.holdsAnything("oidc/reader"))
                .as("a grant in the tenant this console acts in is holding something").isTrue();
    }

    @Test
    void a_deployment_administrator_holds_something_without_any_tenant_grant_of_its_own() {
        // The grant jenreg.ui.admins seeds is deployment-wide, and it is what lets a fresh deployment be
        // administered at all - so an administrator that no tenant has ever heard of still gets in.
        assertThat(access(store(), "oidc/alice").holdsAnything("oidc/alice")).isTrue();
    }

    @Test
    void an_anonymous_token_is_not_a_principal() {
        // Spring's anonymous authentication reports itself as authenticated, so a rule asking only
        // isAuthenticated() would hand the console to an unauthenticated visitor - and would then ask about
        // "anonymousUser", an id that names no subject.
        Set<String> asked = new LinkedHashSet<>();
        ConsoleAccess recording = id -> {
            asked.add(id);
            return true;
        };
        var decision = ConsoleAccessRule.holdsSomething(recording).authorize(
                () -> new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), null);
        assertThat(decision.isGranted()).as("an anonymous token is refused").isFalse();
        assertThat(asked).as("and the policy is not even asked about it").isEmpty();
    }

    private ConsoleAccess access(ArtifactStore store, String admins) {
        Authorization authorization = Authorization.enforcing(store);
        CurrentTenant current = () -> "acme";
        return new GrantedConsoleAccess(authorization, new ConsoleAdministrators(authorization, admins), current);
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }
}
