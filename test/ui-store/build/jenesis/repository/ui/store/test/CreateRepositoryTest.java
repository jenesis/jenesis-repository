package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository created in the console holds the one format it was created with: it is listed at once, its document
 * names the format, the act is audited under the one name the trail owns for it, a repository that already holds a
 * format is left as it is, one that holds content but no format is given one, and the document is not reported as
 * content of the repository.
 */
class CreateRepositoryTest {

    @TempDir
    Path root;

    private final List<String> recorded = new ArrayList<>();

    @Test
    void a_created_repository_is_listed_typed_audited_and_empty() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        RepositoryAdmin admin = new RepositoryAdmin(store, tenant(), ObservationRegistry.NOOP);

        assertThat(lifecycle.create("files", "raw")).isEqualTo(RepositoryType.Creation.CREATED);
        assertThat(admin.repositories()).containsExactly("files");
        assertThat(RepositoryDocument.read(store.scope("acme").scope("files")).map(RepositoryDocument::format))
                .contains("raw");
        assertThat(admin.format("files")).contains("raw");
        assertThat(recorded).containsExactly("acme operator " + AuditActions.REPOSITORY_CREATE + " files");
        assertThat(admin.namespaces("files")).as("the document is not content").isEmpty();
    }

    @Test
    void a_repository_that_holds_a_format_is_left_alone() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        assertThat(lifecycle.create("files", "raw")).isEqualTo(RepositoryType.Creation.CREATED);
        recorded.clear();

        assertThat(lifecycle.create("files", "raw")).isEqualTo(RepositoryType.Creation.UNCHANGED);
        assertThat(recorded).isEmpty();
    }

    @Test
    void a_repository_holding_content_but_no_format_is_given_one() throws IOException {
        ArtifactStore store = store();
        store.scope("acme").scope("files").write("raw/notes.txt", new ByteArrayInputStream("notes".getBytes(UTF_8)));
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");

        assertThat(lifecycle.create("files", "raw")).isEqualTo(RepositoryType.Creation.CREATED);
        assertThat(RepositoryDocument.read(store.scope("acme").scope("files")).map(RepositoryDocument::format))
                .contains("raw");
    }

    @Test
    void a_repository_moves_only_to_a_type_that_holds_everything_it_did() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        assertThat(lifecycle.create("libs", "maven")).isEqualTo(RepositoryType.Creation.CREATED);

        assertThat(lifecycle.create("libs", "java")).as("java holds Maven at the same URLs, and the module layout")
                .isEqualTo(RepositoryType.Creation.RETYPED);
        assertThat(RepositoryDocument.read(store.scope("acme").scope("libs")).map(RepositoryDocument::format))
                .contains("java");
        assertThat(lifecycle.create("libs", "maven")).as("maven does not hold the module layout java served")
                .isEqualTo(RepositoryType.Creation.CONFLICT);
        assertThat(lifecycle.create("libs", "raw")).as("raw holds nothing java did")
                .isEqualTo(RepositoryType.Creation.CONFLICT);
        assertThat(recorded).containsExactly(
                "acme operator " + AuditActions.REPOSITORY_CREATE + " libs",
                "acme operator " + AuditActions.REPOSITORY_RETYPE + " libs to java");
    }

    @Test
    void a_format_no_repository_can_hold_is_refused() {
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store(), tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        assertThatThrownBy(() -> lifecycle.create("files", "not-installed"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not-installed");
        assertThat(recorded).isEmpty();
    }

    @Test
    void a_name_no_repository_may_carry_is_refused() {
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store(), tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        assertThatThrownBy(() -> lifecycle.create("../escape", "raw")).isInstanceOf(IllegalArgumentException.class);
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static CurrentTenant tenant() {
        return new CurrentTenant() {
            @Override
            public String name() {
                return "acme";
            }
        };
    }

    private AuditTrail audit() {
        return new AuditTrail() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String tenant, String actor, String action, String target) {
                recorded.add(tenant + " " + actor + " " + action + " " + target);
            }

            @Override
            public List<Event> query(String tenant, Instant from, Instant to, String action) {
                return List.of();
            }
        };
    }
}
