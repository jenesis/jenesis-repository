package build.jenesis.repository.auth.keylogin.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.auth.keylogin.KeyLoginController;
import build.jenesis.repository.auth.keylogin.KeyLoginKeys;
import build.jenesis.repository.auth.keylogin.KeyLoginScreenController;
import build.jenesis.repository.auth.keylogin.KeyLogins;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.identity.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The issued login keys, administered through the one implementation both surfaces call: issuing binds the principal
 * into the tenant at the chosen role through the ordinary membership file and returns a key that resolves back to
 * that principal; revoking stops it resolving AND removes the membership grant it wrote (so a reissued same-name key
 * inherits nothing); issue and revoke each write an audit event naming the actor; an unknown tenant or a malformed
 * principal is refused. The API answers a refusal {@code 400} and records a key's hash as the actor, and the console
 * screen shows the issued key once and a refusal as its error - each driven here over the same store. Who may call
 * either is the gates' business: the operator gate in front of {@code /api/keylogin}, the console's super-admin floor
 * in front of {@code /ui/settings/}.
 */
public class KeyLoginsTest {

    @TempDir
    Path root;

    private Documents rootStorage;
    private KeyLoginKeys keys;
    private RecordingAuditTrail audit;
    private KeyLogins keyLogins;

    @BeforeEach
    void setUp() throws IOException {
        rootStorage = CacheStorages.documents(root);
        keys = new KeyLoginKeys(rootStorage);
        audit = new RecordingAuditTrail();
        keyLogins = new KeyLogins(keys, rootStorage, Authorization.enforcing(rootStorage.store()), audit);
        // Materialise the tenant so its marker object exists (a real tenant would be created by the console). The
        // marker is a separate object from the membership, which is a key space of one object per member -
        // so provisioning a member no longer conjures a tenant, which is what the existence guard always meant.
        materialiseTenant("acme");
    }

    @Test
    void issuingBindsTheTenantRoleAndReturnsAResolvableKey() throws IOException {
        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "acme", "editor");

        assertThat(issued.key()).startsWith("jkl_");
        assertThat(issued.principal()).isEqualTo("keylogin/octocat");
        assertThat(issued.role()).isEqualTo("editor");
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat"))
                .hasValueSatisfying(user -> assertThat(user.role()).isEqualTo(UserDirectory.Role.EDITOR));
        assertThat(keys.resolve(issued.key())).hasValueSatisfying(resolved ->
                assertThat(resolved.principal()).isEqualTo("keylogin/octocat"));
    }

    @Test
    void revokingStopsTheKeyResolving() throws IOException {
        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "acme", "viewer");
        keyLogins.revoke("admin", issued.id());
        assertThat(keys.resolve(issued.key())).isEmpty();
    }

    @Test
    void revokingRemovesTheMembershipGrantItWrote() throws IOException {
        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "acme", "admin");
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat"))
                .isPresent();

        keyLogins.revoke("admin", issued.id());

        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").find("keylogin/octocat"))
                .as("a revoke reverses the membership grant, so a reissued same-name key inherits nothing").isEmpty();
    }

    @Test
    void issueAndRevokeWriteAuditEvents() throws IOException {
        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "acme", "editor");
        assertThat(audit.has("keylogin.issue", "admin")).as("issue is audited").isTrue();

        keyLogins.revoke("admin", issued.id());
        assertThat(audit.has("keylogin.revoke", "admin")).as("revoke is audited").isTrue();
    }

    @Test
    void aHyphenatedTenantIsAccepted() throws IOException {
        // A hyphenated tenant like acme-corp is valid for artifacts (StoreTenants) and must be equally valid for the
        // key-login path, so issuing a key into it succeeds rather than being refused.
        materialiseTenant("acme-corp");

        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "acme-corp", "editor");

        assertThat(issued.tenant()).isEqualTo("acme-corp");
        assertThat(new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme-corp")
                .find("keylogin/octocat")).isPresent();
    }

    @Test
    void aTenantThatHoldsRepositoriesButNoConsoleMarkerIsATenant() throws IOException {
        // A store that held the tenant's repositories before anyone created it through the console - the store an
        // image is started over - is a tenant by the console's rule, and a key can be issued into it.
        rootStorage.scope("legacy").write("releases/listing", new Properties());

        KeyLogins.Issued issued = keyLogins.issue("admin", "octocat", "octocat", "legacy", "admin");

        assertThat(keys.resolve(issued.key())).isPresent();
    }

    @Test
    void anUnknownTenantAndAMalformedPrincipalAreRefused() {
        assertThatThrownBy(() -> keyLogins.issue("admin", "octocat", "octocat", "no-such-tenant", "editor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown tenant");
        assertThatThrownBy(() -> keyLogins.issue("admin", "bad/principal", "x", "acme", "editor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPrincipalCarryingAnyWhitespaceIsRefused() {
        // All whitespace, not just a literal space: the stored membership line is split on \s+ at read time, so a tab
        // or newline in the principal (or tenant) would split into a forged extra field.
        for (String principal : List.of("oct\tcat", "oct cat", "oct\ncat", "oct\rcat")) {
            assertThatThrownBy(() -> keyLogins.issue("admin", principal, "x", "acme", "editor"))
                    .as("a principal containing whitespace '%s' is refused", principal.replaceAll("\\s", "?"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void listReturnsIssuedPrincipals() throws IOException {
        keyLogins.issue("admin", "alice", "alice", "acme", "viewer");
        assertThat(keyLogins.list()).extracting(KeyLoginKeys.Entry::principal).contains("keylogin/alice");
    }

    @Test
    void theApiAnswersARefusal400AndRecordsTheKeysHashAsTheActor() throws IOException {
        KeyLoginController api = new KeyLoginController(keyLogins);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String operatorKey = Authorization.mint("operator");
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(operatorKey);

        assertThatThrownBy(() -> api.issue(request,
                new KeyLoginController.IssueRequest("octocat", "octocat", "no-such-tenant", "editor")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));

        KeyLogins.Issued issued = api.issue(request,
                new KeyLoginController.IssueRequest("octocat", "octocat", "acme", "admin"));
        assertThat(keys.resolve(issued.key())).isPresent();
        assertThat(audit.has("keylogin.issue", Authorization.hash(operatorKey)))
                .as("the key's hash, never the key, names who issued it").isTrue();
    }

    @Test
    void theScreenShowsAnIssuedKeyOnceAndARefusalAsItsError() throws IOException {
        KeyLoginScreenController screen = new KeyLoginScreenController(keyLogins);

        RedirectAttributesModelMap issued = new RedirectAttributesModelMap();
        assertThat(screen.issue("octocat", "", "acme", "admin", () -> "admin", issued))
                .isEqualTo("redirect:" + KeyLoginScreenController.ROUTE);
        KeyLogins.Issued shown = (KeyLogins.Issued) issued.getFlashAttributes().get("issued");
        assertThat(keys.resolve(shown.key())).as("the key the screen shows is the one that signs in").isPresent();

        RedirectAttributesModelMap refused = new RedirectAttributesModelMap();
        screen.issue("octocat", "", "no-such-tenant", "admin", () -> "admin", refused);
        assertThat((String) refused.getFlashAttributes().get("error")).contains("Unknown tenant");
        assertThat(refused.getFlashAttributes()).doesNotContainKey("issued");

        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(screen.keys(model)).isEqualTo("keylogin/keys");
        assertThat(model.getAttribute("keys")).asList().hasSize(1);
    }

    /** Write the object whose presence IS the tenant, then seed one member - what the console's tenant creation
     *  does, split into its two halves. */
    private void materialiseTenant(String tenant) throws IOException {
        rootStorage.scope(tenant).writeVersioned(UserDirectory.TENANT_FILE, new Properties(), null);
        new UserDirectory(Authorization.enforcing(rootStorage.store()), tenant)
                .put("keylogin/seed", UserDirectory.Role.VIEWER, "seed");
    }
}
