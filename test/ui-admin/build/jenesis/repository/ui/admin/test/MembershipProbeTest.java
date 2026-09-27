package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.admin.security.MembershipCache;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;

import static org.assertj.core.api.Assertions.assertThat;

/** A probe of the console's membership read path, without a Spring context in the way. */
class MembershipProbeTest {

    @TempDir
    Path root;

    private Documents documents;

    private CountingTenants tenants;

    @BeforeEach
    void setUp() {
        documents = CacheStorages.documents(root);
        tenants = new CountingTenants(documents);
    }

    @Test
    void a_member_written_through_the_directory_is_seen_by_the_membership_read() throws IOException {
        Authorization grants = Authorization.enforcing(documents.store());
        tenants.create("navrender");
        new UserDirectory(grants, "navrender").put("github/admin", UserDirectory.Role.ADMIN, "ada");

        assertThat(tenants.all()).as("the tenant exists").containsExactly("navrender");
        assertThat(UserDirectory.roleIn(grants, "navrender", "github/admin"))
                .as("the role reads back through the same authorization")
                .contains(UserDirectory.Role.ADMIN);
        assertThat(new UserDirectory(grants, "navrender").find("github/admin"))
                .as("and through a fresh directory over the same authorization").isPresent();

        Authorization other = Authorization.enforcing(documents.store());
        assertThat(UserDirectory.roleIn(other, "navrender", "github/admin"))
                .as("and through a SECOND authorization over the same store - the console bean's situation")
                .contains(UserDirectory.Role.ADMIN);

        Memberships memberships = new Memberships(grants, tenants, new MembershipCache());
        assertThat(memberships.accessibleTo("github/admin", false))
                .as("so the console sees exactly the one tenant they belong to")
                .containsExactly("navrender");
    }

    @Test
    void a_user_granted_a_role_only_through_a_group_lists_that_tenant_and_loses_it_when_the_group_grant_goes()
            throws IOException {
        // The writes go through one authorization - a directory sync, another node - and the console reads through
        // its own, so the list agrees with the role whichever instance took the write.
        Authorization writer = Authorization.enforcing(documents.store());
        tenants.create("acme");
        tenants.create("globex");
        new UserDirectory(writer, "globex").put("oidc/ada", UserDirectory.Role.VIEWER, "ada");
        writer.groups().addMember("acme", "developers", "oidc/ada");
        writer.setGrant("acme", Authorization.Subject.group("developers"), "*", UserDirectory.Role.EDITOR.rights());

        assertThat(memberships().roleIn("acme", "oidc/ada")).as("the group confers a role in acme")
                .contains(UserDirectory.Role.EDITOR);
        assertThat(memberships().accessibleTo("oidc/ada", false))
                .as("so acme is listed beside the tenant she was granted directly")
                .containsExactly("acme", "globex");

        writer.removeGrant("acme", Authorization.Subject.group("developers"), "*");

        assertThat(memberships().roleIn("acme", "oidc/ada")).as("the group confers nothing now").isEmpty();
        assertThat(memberships().accessibleTo("oidc/ada", false)).as("and acme is no longer listed")
                .containsExactly("globex");
    }

    @Test
    void a_purged_tenant_is_not_listed_though_the_index_still_names_it() throws IOException {
        Authorization writer = Authorization.enforcing(documents.store());
        tenants.create("acme");
        tenants.create("globex");
        new UserDirectory(writer, "acme").put("oidc/ada", UserDirectory.Role.ADMIN, "ada");
        new UserDirectory(writer, "globex").put("oidc/ada", UserDirectory.Role.VIEWER, "ada");

        new TenantPurge(tenants, documents.store(), writer, AuditTrail.NONE, () -> "octo", "operator").delete("acme");

        assertThat(Authorization.enforcing(documents.store()).tenantsOf("oidc/ada"))
                .as("the premise: a purge removes the tenant's grants through the store, past the index")
                .contains("acme");
        assertThat(memberships().accessibleTo("oidc/ada", false))
                .as("each named tenant is confirmed against the role it grants, so the purged one is dropped")
                .containsExactly("globex");
    }

    @Test
    void a_members_tenants_are_read_without_enumerating_every_tenant() throws IOException {
        Authorization writer = Authorization.enforcing(documents.store());
        for (String tenant : List.of("acme", "globex", "initech")) {
            tenants.create(tenant);
        }
        new UserDirectory(writer, "initech").put("oidc/ada", UserDirectory.Role.EDITOR, "ada");
        tenants.walks = 0;

        assertThat(memberships().accessibleTo("oidc/ada", false)).containsExactly("initech");
        assertThat(memberships().accessibleTo("oidc/grace", false)).isEmpty();
        assertThat(tenants.walks).as("a request costs the user's tenants, never the deployment's").isZero();
    }

    /** A cold read on a node of its own: a fresh authorization and a fresh request memo, so an assertion is about
     *  the store rather than about what an earlier read cached. */
    private Memberships memberships() {
        return new Memberships(Authorization.enforcing(documents.store()), tenants, new MembershipCache());
    }

    /** A {@link TenantService} that counts how often every tenant is enumerated. */
    private static final class CountingTenants extends TenantService {

        private int walks;

        private CountingTenants(Documents rootStorage) {
            super(rootStorage);
        }

        @Override
        public List<String> all() {
            walks++;
            return super.all();
        }
    }
}
