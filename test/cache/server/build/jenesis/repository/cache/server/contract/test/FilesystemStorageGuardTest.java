package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The filesystem cache backend confines a project to a direct child of its root: an escaping {@code ..} name is not
 * resolved to a real directory outside the root, and a nested path is not a project even if it exists on disk, so a
 * crafted project name cannot reach outside the cache. A plain name is accepted and provisions the project. The
 * storage root is a subdirectory of the temp dir so the "outside" directory the test creates is still cleaned up.
 */
public class FilesystemStorageGuardTest {

    /**
     * The cache's own root inside the store rooted at {@code store}. Each test configures {@code store} as the
     * repository store and the cache takes one segment inside it, so an assertion about files on disk is made
     * against this and never against the store root itself.
     */
    private static Path cached(Path store) {
        return store.resolve(Scopes.SYSTEM).resolve(Scopes.CACHE);
    }

    @TempDir
    Path base;

    @Test
    void an_escaping_project_name_is_confined_to_the_root() throws IOException {
        Path root = Files.createDirectories(base.resolve("cache"));
        CacheStorage storage = CacheStorages.filesystem(root);
        Files.createDirectory(base.resolve("outside"));
        Files.createDirectories(cached(root).resolve("nested").resolve("child"));
        assertThat(storage.projectExists("../outside"))
                .as("an escaping name does not reach a real directory outside the root").isFalse();
        assertThat(storage.projectExists("nested/child"))
                .as("a nested path, even one that exists, is not a valid project").isFalse();
    }

    @Test
    void a_plain_project_name_is_provisioned() throws IOException {
        Path root = Files.createDirectories(base.resolve("cache"));
        CacheStorage storage = CacheStorages.filesystem(root);
        assertThatCode(() -> storage.createProject("releases")).doesNotThrowAnyException();
        assertThat(storage.projectExists("releases")).isTrue();
    }

    @Test
    void write_file_versioned_is_a_compare_and_set_whose_token_advances() throws IOException {
        Path root = Files.createDirectories(base.resolve("cache"));
        CacheStorage storage = CacheStorages.filesystem(root);
        String path = ".users/login.properties";

        // Absent-precondition: a null expected requires the file be absent; a second create-if-absent loses.
        assertThat(storage.writeFileVersioned(path, props("a", "admin"), null))
                .as("create-if-absent lands when the file is absent").isTrue();
        assertThat(storage.writeFileVersioned(path, props("b", "editor"), null))
                .as("a second create-if-absent loses - the file now exists").isFalse();
        assertThat(storage.readFile(path).getProperty("a")).isEqualTo("admin");

        // A stale token (the one captured before the create) loses; the current token wins and advances.
        Object stale = null;
        Object current = storage.fileVersion(path);
        assertThat(current).as("a written file has a version token").isNotNull();
        assertThat(storage.writeFileVersioned(path, props("c", "viewer"), stale))
                .as("a write with a stale (absent) token loses").isFalse();
        assertThat(storage.writeFileVersioned(path, props("c", "viewer"), current))
                .as("a write with the current token wins").isTrue();
        assertThat(storage.fileVersion(path))
                .as("the token advances on every successful write, so a stale writer cannot masquerade")
                .isNotEqualTo(current);
        assertThat(storage.readFile(path).getProperty("c")).isEqualTo("viewer");
    }

    /**
     * The deterministic half of the config-revalidation guarantee the shared {@code CacheStorageContract} states as
     * {@code CONFIG_VERSION_ADVANCES_ON_REWRITE}: a rewrite must leave this backend's stamp strictly <em>past</em>
     * whatever the document carried, not merely at "now".
     *
     * <p>The contract's own check rewrites back to back and requires the token to differ, which is the guarantee all
     * four backends owe - but on a volume whose timestamps are finer than the cost of a write it cannot manufacture
     * the collision, so it passes either way here. Pinning the stored stamp does manufacture it, and pins the same
     * repair for the two ways it really arises: a filesystem whose timestamp granularity is coarser than the gap
     * between two rewrites (a console double-save, an API applying a batch of dials), and a stamp simply ahead of
     * this node's clock (an NTP step backwards, a restored backup, a {@code cp -p} from another host). In both, a
     * stamp that does not move leaves the server's per-project config cache revalidating successfully against a
     * policy that no longer exists - the operator's lowered size cap or shortened ttl silently not applied.
     *
     * <p>{@code writeFileVersioned} always forced its stamp forward; {@code writeConfig} and {@code writeFile} mint
     * exactly the same kind of token and did not, so both are asserted here.
     */
    @Test
    void a_rewrite_always_moves_the_version_token_past_what_the_document_carried() throws IOException {
        Path root = Files.createDirectories(base.resolve("cache"));
        CacheStorage storage = CacheStorages.filesystem(root);
        FileTime ahead = FileTime.from(Instant.now().plus(Duration.ofHours(1)));

        storage.writeConfig("demo", "cache.properties", props("size", "100"));
        Files.setLastModifiedTime(cached(root).resolve("demo").resolve("cache.properties"), ahead);
        Object beforeConfig = storage.configVersion("demo");
        storage.writeConfig("demo", "cache.properties", props("size", "50"));
        assertThat(storage.readConfig("demo", "cache.properties").getProperty("size"))
                .as("the rewrite is the policy that now stands").isEqualTo("50");
        assertThat(storage.configVersion("demo"))
                .as("the project-config revalidation token must move when the document does, or the per-project "
                        + "config cache goes on serving the superseded caps. A STAMP alone could not promise it - "
                        + "the rewrite lands an hour EARLIER than the stamp the document carried - which is why the "
                        + "token names an incarnation (the stamp plus a digest of the bytes) and not a moment")
                .isNotEqualTo(beforeConfig);

        String path = ".users/login.properties";
        storage.writeFile(path, props("acme", "admin"));
        Files.setLastModifiedTime(cached(root).resolve(".users").resolve("login.properties"), ahead);
        Object beforeFile = storage.fileVersion(path);
        storage.writeFile(path, props("acme", "reader"));
        assertThat(storage.readFile(path).getProperty("acme")).isEqualTo("reader");
        assertThat(storage.fileVersion(path))
                .as("the config-tree token moves too - it is the same token writeFileVersioned compares against, so "
                        + "one that did not move lets a writer holding the pre-rewrite token land a stale write")
                .isNotEqualTo(beforeFile);
    }

    @Test
    void a_stale_write_temp_left_by_a_crash_is_reclaimed_by_the_entry_sweep() throws IOException {
        Path root = Files.createDirectories(base.resolve("cache"));
        CacheStorage storage = CacheStorages.filesystem(root);
        // A store() writes a .upload*.tmp then atomically moves it into place; a crash before the move leaves the
        // temp behind, filtered out of every listing and therefore invisible - which means the reaper, size cap and
        // free-space reclaim would never see it and the volume would ratchet toward a permanent 507 FULL. The
        // reclaim now lives in the store that CREATES the temps rather than in a cache backend that had to know
        // their shape; this asserts the cache still gets it.
        Path stepDir = Files.createDirectories(cached(root).resolve("demo").resolve("aa"));
        Path entry = Files.writeString(stepDir.resolve("bb"), "a cached blob");   // a real hex-named cache entry
        Path staleTemp = Files.createTempFile(stepDir, ".upload", ".tmp");
        Files.writeString(staleTemp, "half-written orphan");
        Files.setLastModifiedTime(staleTemp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Path freshTemp = Files.createTempFile(stepDir, ".upload", ".tmp");              // a concurrent in-flight store()'s temp
        Files.writeString(freshTemp, "still being written");

        List<CacheStorage.Stored> entries = new ArrayList<>();
        storage.entries("demo", null, CacheStorage.PAGE, entries::add);

        assertThat(entries).as("only the hex-named entry counts, never a temp").hasSize(1);
        assertThat(staleTemp).as("the stale crash leftover is reaped by the same sweep that lists entries")
                .doesNotExist();
        assertThat(freshTemp).as("a fresh temp (an in-flight write) is left alone by the grace window").isRegularFile();
        assertThat(entry).as("the real entry is untouched").isRegularFile();
    }

    private static Properties props(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
