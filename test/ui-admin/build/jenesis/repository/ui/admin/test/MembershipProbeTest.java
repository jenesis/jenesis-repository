package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.admin.security.MembershipCache;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.TenantService;

import static org.assertj.core.api.Assertions.assertThat;

/** A probe of the console's membership read path, without a Spring context in the way. */
class MembershipProbeTest {

    @TempDir
    Path root;

    @Test
    void a_member_written_through_the_directory_is_seen_by_the_membership_read() throws IOException {
        Documents documents = CacheStorages.documents(root);
        Authorization grants = Authorization.enforcing(documents.store());
        TenantService tenants = new TenantService(documents);
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

        Memberships memberships = new Memberships(documents, grants, tenants, new MembershipCache());
        assertThat(memberships.accessibleTo("github/admin", false))
                .as("so the console sees exactly the one tenant they belong to")
                .containsExactly("navrender");
    }
}
