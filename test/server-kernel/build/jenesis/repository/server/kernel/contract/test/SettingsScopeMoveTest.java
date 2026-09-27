package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsScopeMove;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time move of what a deployment kept outside the settings catalogue into its tenant, repository and project
 * settings, over a real filesystem store seeded the way the former code wrote it: the moved value is the one that
 * then wins, a second run - after a completed first, or after one that stopped part way - writes nothing, an
 * operator's value set since is never overwritten, and every former document is left where it was.
 */
class SettingsScopeMoveTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void seed() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // A tenant's ceilings as the credential space kept them.
        write(".system/auth/acme/quota", "max-bytes=4096\n");
        write(".system/auth/acme/ratelimit", "permits-per-minute=120\n");
        // A tenant's own definition of one of its repositories.
        StoredSettings.write(store.scope("acme"), Map.of("repositories.mirror", "fallback https://repo1.maven.org/maven2"));
        new RepositoryDocument("maven", Instant.now()).create(store.scope("acme").scope("mirror"));
        // A repository's stored retention policy: two newest kept, and no age rule of its own.
        new RepositoryDocument("maven", Instant.now()).create(store.scope("acme").scope("libs"));
        write("acme/libs/retention", "keepLast=2\nnotDownloadedFor=PT720H\n");
        // A build-cache project's policy file.
        write(".system/cache/acme/agents/cache.properties", "size=1048576\nlru=false\nttl=P7D\n");
    }

    private void write(String key, String content) throws IOException {
        store.writeVersioned(key, content.getBytes(StandardCharsets.UTF_8), null);
    }

    private SettingsScopeMove move() {
        return new SettingsScopeMove(store, repository -> new StoreRepositoryInventory(repository).formerRetention());
    }

    @Test
    void each_former_value_is_moved_into_the_settings_of_its_level() throws IOException {
        SettingsScopeMove.Moved moved = move().run();

        assertThat(StoredSettings.read(store.scope("acme")))
                .containsEntry("tenant-quota", "4096").containsEntry("rate-limit", "120");
        assertThat(StoredSettings.read(store.scope("acme").scope("mirror")))
                .containsEntry("routing", "fallback https://repo1.maven.org/maven2");
        assertThat(StoredSettings.read(store.scope("acme").scope("libs")))
                .containsEntry("keep-last", "2")
                .containsEntry("not-downloaded-for", "PT720H")
                .as("a rule the stored policy left unset was off for it, whatever the deployment set")
                .containsEntry("max-age", "none").containsEntry("prerelease-expiry", "none");
        assertThat(StoredSettings.read(StoredSettings.project(store, "acme", "agents")))
                .containsEntry("project-size", "1048576").containsEntry("project-lru", "false")
                .containsEntry("project-ttl", "P7D");
        assertThat(moved.written()).containsKeys("acme", "acme/libs", "acme/mirror", "project acme/agents");
    }

    @Test
    void the_moved_value_is_the_one_that_wins_over_the_deployments() throws IOException {
        Settings settings = new Settings(store);
        LiveConfig live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(), _ -> null);
        settings.set("max-age", "P1D");
        settings.set("keep-last", "10");
        settings.set("tenant-quota", "999999");

        move().run();
        settings.refresh();

        assertThat(live.retention("acme", "libs").keepLast()).isEqualTo(2);
        assertThat(live.retention("acme", "libs").maxAge())
                .as("the deployment's one-day rule does not reach a repository whose own policy had none")
                .isNull();
        assertThat(live.retention("acme", "other").maxAge()).as("a repository with no policy of its own inherits it")
                .isEqualTo(Duration.ofDays(1));
        assertThat(live.effective("acme", "tenant-quota", "0")).isEqualTo("4096");
    }

    @Test
    void a_second_run_writes_nothing_and_never_overwrites_what_an_operator_set_since() throws IOException {
        move().run();
        StoredSettings.write(store.scope("acme").scope("libs"), Map.of("keep-last", "20"));

        assertThat(move().run().written()).as("a completed move does not run again").isEmpty();
        store.delete(SettingsScopeMove.DONE);
        assertThat(move().run().written()).as("and a move that stopped part way resumes writing nothing twice")
                .isEmpty();
        assertThat(StoredSettings.read(store.scope("acme").scope("libs"))).containsEntry("keep-last", "20");
    }

    @Test
    void every_former_document_is_left_where_it_was() throws IOException {
        move().run();

        assertThat(store.exists(".system/auth/acme/quota")).isTrue();
        assertThat(store.exists(".system/auth/acme/ratelimit")).isTrue();
        assertThat(store.exists("acme/libs/retention")).isTrue();
        assertThat(store.exists(".system/cache/acme/agents/cache.properties")).isTrue();
        assertThat(StoredSettings.read(store.scope("acme")))
                .as("the tenant's definition stays in its document, inert")
                .containsEntry("repositories.mirror", "fallback https://repo1.maven.org/maven2");
        assertThat(store.exists(SettingsScopeMove.DONE)).isTrue();
    }

    @Test
    void a_tenant_definition_of_a_repository_that_does_not_exist_is_not_moved() throws IOException {
        StoredSettings.write(store.scope("acme"), Map.of("repositories.ghost", "writable"));

        move().run();

        assertThat(store.scope("acme").list("")).as("no settings document creates a repository")
                .doesNotContain("ghost");
    }
}
