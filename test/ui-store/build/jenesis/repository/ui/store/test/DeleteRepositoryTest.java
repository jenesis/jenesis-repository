package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository's description and its deletion. A description is given at creation or later, kept by a retype and
 * refused when longer than a repository takes. A deletion takes the repository's document at once - it answers no
 * request from then on - removes everything it held off the request path, and leaves the name free again; while it
 * runs the repository reads as being deleted and cannot be created again, a deletion a node stopped part way is
 * resumed by deleting again, and a name that holds no repository deletes nothing.
 */
class DeleteRepositoryTest {

    @TempDir
    Path root;

    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @Test
    void a_description_is_given_at_creation_changed_later_and_kept_by_a_retype() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = lifecycle(store);
        RepositoryAdmin admin = new RepositoryAdmin(store, tenant(), ObservationRegistry.NOOP);

        assertThat(lifecycle.create("libs", "maven", "  The platform's\nreleases ")).isEqualTo(RepositoryType.Creation.CREATED);
        assertThat(admin.document("libs").map(RepositoryDocument::description))
                .as("stored as one trimmed line").contains("The platform's releases");

        assertThat(lifecycle.describe("libs", "Platform releases")).isTrue();
        assertThat(lifecycle.create("libs", "java")).isEqualTo(RepositoryType.Creation.RETYPED);
        assertThat(RepositoryDocument.read(store.scope("acme").scope("libs")).map(RepositoryDocument::description))
                .as("a retype keeps it").contains("Platform releases");
        assertThat(recorded).contains("acme operator " + AuditActions.REPOSITORY_DESCRIBE + " libs");

        assertThatThrownBy(() -> lifecycle.describe("libs", "x".repeat(RepositoryDocument.DESCRIPTION_LIMIT + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lifecycle.describe("nothing-here", "a description")).as("no repository, nothing described")
                .isFalse();
    }

    @Test
    void a_deleted_repository_stops_answering_at_once_and_everything_it_held_goes() throws Exception {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = lifecycle(store);
        RepositoryAdmin admin = new RepositoryAdmin(store, tenant(), ObservationRegistry.NOOP);
        lifecycle.create("files", "raw", "Build outputs");
        ArtifactStore scope = store.scope("acme").scope("files");
        for (int index = 0; index < 50; index++) {
            scope.write("raw/dir" + (index % 5) + "/file" + index + ".txt",
                    new ByteArrayInputStream(("content " + index).getBytes(UTF_8)));
        }
        lifecycle.create("kept", "raw");

        assertThat(lifecycle.delete("files")).isEqualTo(RepositoryRemoval.Begun.STARTED);
        assertThat(RepositoryDocument.read(scope)).as("its document goes before the request returns").isEmpty();
        assertThat(recorded).contains("acme operator " + AuditActions.REPOSITORY_DELETE + " files");

        await(() -> !admin.repositories().contains("files"),
                () -> "still listed; the scope holds " + scope.list("") + " and scans as " + scanned(scope));
        assertThat(scope.list("")).as("nothing it held is left").isEmpty();
        assertThat(admin.removing("files")).as("and the marker went last").isFalse();
        assertThat(admin.repositories()).as("another repository is untouched").containsExactly("kept");

        assertThat(lifecycle.create("files", "raw")).as("the name is free again")
                .isEqualTo(RepositoryType.Creation.CREATED);
    }

    @Test
    void a_repository_being_deleted_reads_so_cannot_be_created_again_and_is_resumed_by_deleting_again()
            throws Exception {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = lifecycle(store);
        RepositoryAdmin admin = new RepositoryAdmin(store, tenant(), ObservationRegistry.NOOP);
        lifecycle.create("files", "raw");
        ArtifactStore scope = store.scope("acme").scope("files");
        scope.write("raw/a.txt", new ByteArrayInputStream("a".getBytes(UTF_8)));

        // A node that began the removal and stopped before the purge: the marker stands, the document is gone.
        assertThat(RepositoryRemoval.begin(scope)).isEqualTo(RepositoryRemoval.Begun.STARTED);
        assertThat(admin.removing("files")).isTrue();
        assertThatThrownBy(() -> lifecycle.create("files", "raw")).as("a name still being deleted is not recreated")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("being deleted");

        assertThat(lifecycle.delete("files")).isEqualTo(RepositoryRemoval.Begun.RESUMED);
        await(() -> !admin.repositories().contains("files"),
                () -> "still listed; the scope holds " + scope.list("") + " and scans as " + scanned(scope));
        assertThat(scope.list("")).isEmpty();
    }

    @Test
    void a_name_that_holds_no_repository_deletes_nothing() throws IOException {
        ArtifactStore store = store();
        RepositoryLifecycle lifecycle = lifecycle(store);
        store.scope("acme").scope("untyped").write("raw/a.txt", new ByteArrayInputStream("a".getBytes(UTF_8)));

        assertThat(lifecycle.delete("absent")).isEqualTo(RepositoryRemoval.Begun.ABSENT);
        assertThat(lifecycle.delete("untyped")).as("content with no document is not a repository to delete")
                .isEqualTo(RepositoryRemoval.Begun.ABSENT);
        assertThat(store.scope("acme").scope("untyped").list("")).isNotEmpty();
        assertThat(recorded).isEmpty();
    }

    private static List<String> scanned(ArtifactStore scope) throws IOException {
        List<String> keys = new ArrayList<>();
        scope.scan("", "", 100, listed -> keys.add(listed.key()));
        return keys;
    }

    private static void await(Callable<Boolean> condition, Callable<String> state) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.call()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the removal did not finish within 30 seconds: " + state.call());
            }
            Thread.sleep(50);
        }
    }

    private RepositoryLifecycle lifecycle(ArtifactStore store) {
        return new RepositoryLifecycle(store, tenant(), ObservationRegistry.NOOP, audit(), () -> "operator");
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
