package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two administrators granting at the same moment both keep their grant.
 *
 * <p>The falsifier for a defect this suite was written from. {@code setGrant} read the subject's whole grants
 * object, set one scope in it and put the object back <em>unconditionally</em>. Two writers granting DIFFERENT
 * scopes to one subject therefore raced, and the loser's grant was overwritten with no error and nothing to notice
 * it by - on the object that decides what a caller may do. A revoke racing a grant had the same shape, so a
 * revoked scope could come back.
 *
 * <p>What makes it a defect rather than a theoretical race is who does it. The console's member screen, SCIM's
 * directory sync and the key-login issue path all write here, from different requests and different replicas, so
 * there is no lock that could have covered it - which is why the answer is a compare-and-set that re-reads and
 * re-applies, and throws when it has genuinely lost rather than pretending it kept the record.
 *
 * <p>Run against the unconditional write these fail: the last writer's object is the only one that survives, so
 * one of the two scopes is simply absent.
 */
class ConcurrentGrantTest {

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
    void concurrent_grants_of_different_scopes_to_one_subject_all_survive() throws Exception {
        Authorization.Subject octocat = Authorization.Subject.principal("github/octocat");
        int scopes = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < scopes; index++) {
            String scope = "repo-" + index;
            Thread writer = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    authorization.setGrant("acme", octocat, scope, Authorization.REPOSITORY_READ);
                } catch (Throwable failed) {
                    failures.add(failed);
                }
            });
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        assertThat(failures).as("no writer gave up its grant").isEmpty();
        assertThat(authorization.grants("acme", octocat))
                .as("every scope granted concurrently is still held - a lost one is a right an administrator "
                        + "believes they gave and nobody has")
                .hasSize(scopes);
        for (int index = 0; index < scopes; index++) {
            assertThat(authorization.authorize("acme", octocat, "repo-" + index, Authorization.REPOSITORY_READ))
                    .as("repo-%d", index).isEqualTo(Authorization.Decision.ALLOWED);
        }
    }

    @Test
    void concurrent_grants_to_one_credential_all_survive() throws Exception {
        // The same object, reached the way it is reached today: this is not a new hazard that arrived with
        // principals, it is one that was always there for keys.
        String key = Authorization.mint("acme");
        String hash = Authorization.hash(key);
        int scopes = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int index = 0; index < scopes; index++) {
            String scope = "repo-" + index;
            Thread writer = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    authorization.setGrant("acme", hash, scope, Authorization.REPOSITORY_READ);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            });
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }
        assertThat(authorization.credential("acme", hash)).hasValueSatisfying(credential ->
                assertThat(credential.grants()).as("every concurrently granted scope survives").hasSize(scopes));
    }
}
