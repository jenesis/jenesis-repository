package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.identity.UserDirectory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page of members holds members, however many principals the tenant's groups name besides them: a principal a group
 * names holds no role of its own, so it is read and skipped, and the page reads on past it rather than answering a
 * page with nobody on it and a cursor to the next.
 */
public class UserDirectoryPageTest {

    @TempDir
    private Path root;

    @Test
    public void a_page_reads_past_the_principals_only_a_group_names() throws IOException {
        Authorization authorization = Authorization.enforcing(CacheStorages.documents(root).store());
        for (int i = 0; i < 5; i++) {
            authorization.groups().addMember("acme", "crowd", "a/fan-" + i);
        }
        UserDirectory directory = new UserDirectory(authorization, "acme");
        for (int i = 0; i < 3; i++) {
            directory.put("b/member-" + i, UserDirectory.Role.VIEWER, null);
        }

        UserDirectory.Page first = directory.page(null, 2);
        assertThat(first.users()).extracting(UserDirectory.User::id).containsExactly("b/member-0", "b/member-1");
        assertThat(first.nextCursor()).isPresent();

        UserDirectory.Page second = directory.page(first.nextCursor().get(), 2);
        assertThat(second.users()).extracting(UserDirectory.User::id).containsExactly("b/member-2");
        assertThat(second.nextCursor()).as("the last member ends the enumeration").isEmpty();
    }
}
