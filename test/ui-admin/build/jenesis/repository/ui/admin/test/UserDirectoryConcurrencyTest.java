package build.jenesis.repository.ui.admin.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.identity.UserDirectory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console membership is written both by the admin screen (a singleton {@link UserDirectory} bean) and by SCIM
 * provisioning (a fresh {@link UserDirectory} per request), so a blind read-modify-write let two concurrent edits
 * last-writer-wins, silently dropping the member the loser added - and a {@code synchronized} guards nothing across
 * the per-request SCIM instances. These tests drive the membership through <em>separate</em> {@link UserDirectory}
 * instances over one real {@link Documents} - the exact SCIM shape - and assert no member is lost.
 *
 * <p>Each member is its own small object - a grant to that principal - so two writes to two members do
 * not contend at all and the compare-and-set only ever arbitrates writes to the <em>same</em> member. That is what
 * these tests now pin: the property is unchanged (no member is lost), and the reason it holds is the storage shape
 * rather than sixteen retries over one shared document.
 */
public class UserDirectoryConcurrencyTest {

    @TempDir
    private Path root;

    @Test
    public void concurrent_provisioning_through_fresh_instances_loses_no_member() throws Exception {
        int members = 24;
        // A fresh UserDirectory per task, as SCIM builds one per request; all over the same tenant storage.
        CyclicBarrier start = new CyclicBarrier(members);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < members; index++) {
            String id = "github/" + index;
            UserDirectory.Role role = UserDirectory.Role.values()[index % UserDirectory.Role.values().length];
            tasks.add(() -> {
                start.await();
                new UserDirectory(Authorization.enforcing(CacheStorages.documents(root).store()), "acme").put(id, role, "user" + id);
                return null;
            });
        }
        run(tasks);

        List<UserDirectory.User> stored = new UserDirectory(Authorization.enforcing(CacheStorages.documents(root).store()), "acme").page(null, 1000).users();
        assertThat(stored).as("every concurrently provisioned member survived the compare-and-set retry")
                .hasSize(members);
        Set<String> ids = new TreeSet<>();
        stored.forEach(user -> ids.add(user.id()));
        for (int index = 0; index < members; index++) {
            assertThat(ids).contains("github/" + index);
        }
    }

    @Test
    public void a_concurrent_add_and_remove_do_not_drop_the_bystander() throws Exception {
        // Seed a bystander that neither concurrent editor touches; if a lost update reverts the file, it vanishes.
        new UserDirectory(Authorization.enforcing(CacheStorages.documents(root).store()), "acme").put("github/keep", UserDirectory.Role.ADMIN, "keeper");

        int editors = 16;
        CyclicBarrier start = new CyclicBarrier(editors);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < editors; index++) {
            String id = "github/e" + index;
            boolean add = index % 2 == 0;
            tasks.add(() -> {
                start.await();
                UserDirectory directory = new UserDirectory(Authorization.enforcing(CacheStorages.documents(root).store()), "acme");
                if (add) {
                    directory.put(id, UserDirectory.Role.VIEWER, "e" + id);
                } else {
                    directory.remove(id); // removing an absent id is a no-op on that member's own key alone
                }
                return null;
            });
        }
        run(tasks);

        List<UserDirectory.User> stored = new UserDirectory(Authorization.enforcing(CacheStorages.documents(root).store()), "acme").page(null, 1000).users();
        Set<String> ids = new TreeSet<>();
        stored.forEach(user -> ids.add(user.id()));
        assertThat(ids).as("the untouched bystander is never dropped by a racing edit").contains("github/keep");
        for (int index = 0; index < editors; index += 2) {
            assertThat(ids).as("every added member survived").contains("github/e" + index);
        }
    }

    @Test
    public void a_member_is_its_own_small_object_so_a_lookup_never_reads_the_directory() throws Exception {
        Documents storage = CacheStorages.documents(root);
        UserDirectory directory = new UserDirectory(Authorization.enforcing(storage.store()), "acme");
        directory.put("github/1", UserDirectory.Role.ADMIN, "ada");
        directory.put("github/2", UserDirectory.Role.VIEWER, "vic");

        // The whole tenant's membership was once ONE .users/login.properties object, which is why nothing above it
        // could page and why find(id) - and the per-request role check - read every member to answer about one.
        // The shape is the fix, so the shape is what this pins: one small object per member, at a key composed from
        // that member's id.
        //
        // The key moved when a member became a principal subject: it is a grant in the authorization store now,
        // beside the grants a minted key holds, rather than a document of its own beside the tenant marker. The
        // property is unchanged and so is the reason for it - what changed is that a person's rights and a key's
        // rights are the same kind of thing in the same place. The id is percent-encoded into one segment, which
        // is why github/1 keys as github%2F1.
        String key = ".system/auth/acme/principal/github%2F1/grants";
        assertThat(storage.version(key)).as("the member is stored under its own key").isNotNull();
        assertThat(storage.read(key).getProperty("*"))
                .as("holding the rights its role bundles, at the tenant-wide scope")
                .contains("manage:write");
        assertThat(storage.version(".users/login.properties"))
                .as("and there is no shared membership document left to read whole").isNull();

        // The role check reads that key and nothing else, so it costs one small object whatever the population.
        assertThat(UserDirectory.roleIn(Authorization.enforcing(storage.store()), "acme", "github/1")).contains(UserDirectory.Role.ADMIN);
        assertThat(UserDirectory.roleIn(Authorization.enforcing(storage.store()), "acme", "github/absent")).isEmpty();

        // Removing a member removes its object; every other member's is untouched.
        directory.remove("github/1");
        assertThat(storage.version(key)).isNull();
        assertThat(directory.find("github/2")).isPresent();
    }

    private static void run(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get(); // surface any thread's failure (e.g. the bounded-retry give-up)
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
