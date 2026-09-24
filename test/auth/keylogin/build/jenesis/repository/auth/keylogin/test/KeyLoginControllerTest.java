package build.jenesis.repository.auth.keylogin.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.auth.keylogin.KeyLoginController;
import build.jenesis.repository.auth.keylogin.KeyLoginKeys;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.identity.UserDirectory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operator admin API: only a super-admin may list, issue or revoke; issuing binds the principal into the tenant at
 * the chosen role through the ordinary membership file and returns a key that resolves back to that principal; revoking
 * stops it resolving AND removes the membership grant it wrote (so a reissued same-name key inherits nothing); issue and
 * revoke each write an audit event; an unknown tenant or malformed principal is rejected.
 */
public class KeyLoginControllerTest {

    @TempDir
    Path root;

    private Documents rootStorage;
    private KeyLoginKeys keys;
    private RecordingAuditTrail audit;
    private KeyLoginController controller;

    @BeforeEach
    void setUp() throws IOException {
        rootStorage = CacheStorages.documents(root);
        keys = new KeyLoginKeys(rootStorage);
        audit = new RecordingAuditTrail();
        controller = new KeyLoginController(keys, rootStorage,
                Authorization.enforcing(rootStorage.store()), audit);
        // Materialise the tenant so its marker object exists (a real tenant would be created by the console). Since
        // the marker is a separate object from the membership, which is a key space of one object per member -
        // so provisioning a member no longer conjures a tenant, which is what the existence guard always meant.
        materialiseTenant("acme");
    }

    private static Authentication superadmin() {
        return UsernamePasswordAuthenticationToken.authenticated("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private static Authentication member() {
        return UsernamePasswordAuthenticationToken.authenticated("keylogin/user", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void issuingBindsTheTenantRoleAndReturnsAResolvableKey() throws IOException {
        KeyLoginController.IssuedView view = controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "editor"));

        assertThat(view.key()).startsWith("jkl_");
        assertThat(view.principal()).isEqualTo("keylogin/octocat");
        assertThat(view.role()).isEqualTo("editor");
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat"))
                .hasValueSatisfying(user -> assertThat(user.role()).isEqualTo(UserDirectory.Role.EDITOR));
        assertThat(keys.resolve(view.key())).hasValueSatisfying(resolved ->
                assertThat(resolved.principal()).isEqualTo("keylogin/octocat"));
    }

    @Test
    void revokingStopsTheKeyResolving() throws IOException {
        KeyLoginController.IssuedView view = controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "viewer"));
        controller.revoke(superadmin(), view.id());
        assertThat(keys.resolve(view.key())).isEmpty();
    }

    @Test
    void revokingRemovesTheMembershipGrantItWrote() throws IOException {
        KeyLoginController.IssuedView view = controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "admin"));
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat")).isPresent();

        controller.revoke(superadmin(), view.id());

        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat"))
                .as("a revoke reverses the membership grant, so a reissued same-name key inherits nothing").isEmpty();
    }

    @Test
    void issueAndRevokeWriteAuditEvents() throws IOException {
        KeyLoginController.IssuedView view = controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "editor"));
        assertThat(audit.has("keylogin.issue", "admin")).as("issue is audited").isTrue();

        controller.revoke(superadmin(), view.id());
        assertThat(audit.has("keylogin.revoke", "admin")).as("revoke is audited").isTrue();
    }

    @Test
    void nonSuperadminIsRefused() {
        assertThatThrownBy(() -> controller.list(member()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> controller.issue(member(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "editor")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void aHyphenatedTenantIsAcceptedByTheKeyLoginPath() throws IOException {
        // A hyphenated tenant like acme-corp is valid for artifacts (StoreTenants) and must be equally valid for the
        // key-login path: Names.isTenant now admits the hyphen, so issuing a key into it succeeds rather than 400ing.
        materialiseTenant("acme-corp");

        KeyLoginController.IssuedView view = controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme-corp", "editor"));

        assertThat(view.tenant()).isEqualTo("acme-corp");
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme-corp").find("keylogin/octocat")).isPresent();
    }

    @Test
    void unknownTenantIsRejected() {
        assertThatThrownBy(() -> controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("octocat", "octocat", "no-such-tenant", "editor")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void malformedPrincipalIsRejected() {
        assertThatThrownBy(() -> controller.issue(superadmin(),
                new KeyLoginController.IssueRequest("bad/principal", "x", "acme", "editor")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void aPrincipalCarryingAnyWhitespaceIsRejected() {
        // token() rejects ALL whitespace, not just a literal space - the stored membership line is split on \s+ at read
        // time, so a tab or newline in the principal (or tenant) would split into a forged extra field. Each of these
        // must be a 400, not silently accepted on the strength of an inner check honouring the same charset.
        for (String principal : List.of("oct\tcat", "oct cat", "oct\ncat", "oct\rcat")) {
            assertThatThrownBy(() -> controller.issue(superadmin(),
                    new KeyLoginController.IssueRequest(principal, "x", "acme", "editor")))
                    .as("a principal containing whitespace '%s' is rejected", principal.replaceAll("\\s", "?"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        }
    }

    @Test
    void listReturnsIssuedPrincipals() throws IOException {
        controller.issue(superadmin(), new KeyLoginController.IssueRequest("alice", "alice", "acme", "viewer"));
        assertThat(controller.list(superadmin())).extracting(KeyLoginKeys.Entry::principal)
                .contains("keylogin/alice");
    }
    /** Write the object whose presence IS the tenant, then seed one member - what the console's tenant creation
     *  does, split into its two halves since the earlier work. */
    private void materialiseTenant(String tenant) throws IOException {
        rootStorage.scope(tenant).writeVersioned(UserDirectory.TENANT_FILE, new Properties(), null);
        new UserDirectory(Authorization.enforcing(rootStorage.store()), tenant).put("keylogin/seed", UserDirectory.Role.VIEWER, "seed");
    }

}
