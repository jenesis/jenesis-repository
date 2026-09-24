package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository created in the console before anything is published into it: it is listed at once, the act is
 * audited under the one name the trail owns for it, a repository that already exists is left as it is, and the
 * creation marker is not reported as content of the repository.
 */
class CreateRepositoryTest {

    @TempDir
    Path root;

    private final List<String> recorded = new ArrayList<>();

    @Test
    void a_created_repository_is_listed_audited_and_empty() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        RepositoryAdmin admin = new RepositoryAdmin(store, tenant(), ObservationRegistry.NOOP);

        assertThat(lifecycle.create("releases")).isTrue();
        assertThat(admin.repositories()).containsExactly("releases");
        assertThat(recorded).containsExactly("acme operator " + AuditActions.REPOSITORY_CREATE + " releases");
        assertThat(admin.namespaces("releases")).as("the marker is not content").isEmpty();
    }

    @Test
    void a_repository_that_exists_is_left_alone() throws IOException {
        ArtifactStore store = store();
        store.scope("acme").scope("releases").write("raw/notes.txt", new ByteArrayInputStream("notes".getBytes(UTF_8)));
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");

        assertThat(lifecycle.create("releases")).isFalse();
        assertThat(store.scope("acme").scope("releases").exists(Scopes.CREATED)).isFalse();
        assertThat(recorded).isEmpty();
    }

    @Test
    void a_name_no_repository_may_carry_is_refused() {
        RepositoryLifecycle lifecycle = new RepositoryLifecycle(store(), tenant(), ObservationRegistry.NOOP, audit(),
                () -> "operator");
        assertThatThrownBy(() -> lifecycle.create("../escape")).isInstanceOf(IllegalArgumentException.class);
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
