package build.jenesis.repository.auth.ldap.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.auth.ldap.Directory;
import build.jenesis.repository.auth.ldap.LdapProperties;
import build.jenesis.repository.auth.ldap.LdapSignIn;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.identity.UserDirectory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * A directory sign-in end to end below the web layer: what the directory says decides who is signed in, and what
 * their groups decide lands in the deployment's own membership records.
 */
class LdapSignInTest {

    @TempDir
    Path root;

    private Authorization authorization;
    private final List<String> audited = new ArrayList<>();
    private final AtomicBoolean throttled = new AtomicBoolean();

    /** Alice is in the developers and the platform administrators; nobody else has a password here. */
    private final Directory directory = (username, password) -> username.equalsIgnoreCase("alice")
            && password.equals("wonderland")
            ? Optional.of(new Directory.Account(username, Set.of("developers", "platform-admins")))
            : Optional.empty();

    @BeforeEach
    void store() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    private AuthenticationProvider signIn(LdapProperties properties) {
        AuditTrail trail = new AuditTrail() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String tenant, String actor, String action, String target) {
                audited.add(action + " " + actor);
            }

            @Override
            public List<Event> query(String tenant, Instant from, Instant to, String action) {
                return List.of();
            }
        };
        return new LdapSignIn(directory, properties, authorization,
                (id, name) -> List.of(new SimpleGrantedAuthority("ROLE_USER")),
                (key, permits) -> !throttled.get(), trail);
    }

    private static LdapProperties properties() {
        LdapProperties properties = new LdapProperties();
        properties.setUrl("ldaps://directory.example.com");
        properties.setUserDnPattern("uid={0},ou=people,dc=example,dc=com");
        properties.setTenants("acme, globex");
        return properties;
    }

    @Test
    void an_account_signs_in_under_a_case_folded_id_and_its_groups_become_memberships() throws IOException {
        Authentication signed = signIn(properties())
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("Alice", "wonderland"));

        assertThat(signed.getName()).as("one person whatever case they type").isEqualTo("ldap/alice");
        assertThat(signed.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        for (String tenant : List.of("acme", "globex")) {
            assertThat(authorization.members(tenant, "developers", null, 10).ids()).containsExactly("ldap/alice");
        }
        assertThat(audited).containsExactly("login ldap/alice");
    }

    @Test
    void a_role_granted_to_a_directory_group_reaches_the_console() throws IOException {
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                UserDirectory.Role.EDITOR.rights());

        signIn(properties()).authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wonderland"));

        assertThat(UserDirectory.roleIn(authorization, "acme", "ldap/alice"))
                .as("granted to the group, held by its member, seen by the console")
                .contains(UserDirectory.Role.EDITOR);
    }

    @Test
    void the_administrators_group_confers_super_admin() {
        LdapProperties properties = properties();
        properties.setAdminGroup("platform-admins");

        Authentication signed = signIn(properties)
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wonderland"));

        assertThat(signed.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_SUPERADMIN");
    }

    @Test
    void a_refused_password_signs_nobody_in_and_is_audited() {
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() -> signIn(properties())
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "guess")));
        assertThat(audited).containsExactly("login.failed ldap/alice");
    }

    @Test
    void a_throttled_client_is_refused_before_the_directory_is_asked() {
        throttled.set(true);
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() -> signIn(properties())
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wonderland")));
        assertThat(audited).containsExactly("login.throttled anonymous");
    }

    @Test
    void a_plaintext_url_is_refused_unless_start_tls_or_an_explicit_opt_out_says_otherwise() {
        LdapProperties plain = properties();
        plain.setUrl("ldap://directory.example.com");
        assertThatIllegalStateException().isThrownBy(plain::validate).withMessageContaining("in the clear");

        plain.setStartTls(true);
        plain.validate();

        plain.setStartTls(false);
        plain.setAllowPlaintext(true);
        plain.validate();
    }

    @Test
    void a_configuration_that_cannot_find_anyone_is_refused() {
        LdapProperties nowhere = properties();
        nowhere.setUserDnPattern("");
        assertThatIllegalStateException().isThrownBy(nowhere::validate).withMessageContaining("user-dn-pattern");
    }
}
