package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.store.RunningMarker;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Standing requests and the running marker: the two small objects that make a walk happen without a clock. */
class RequestsTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @AfterEach
    void uninstall() {
        Requests.installRoot(null);
    }

    @Test
    void a_request_stands_until_cleared_and_a_repeated_one_is_one_object() throws IOException {
        assertThat(Requests.pending(store, Requests.WALK)).isEmpty();
        Requests.request(store, Requests.WALK, "node a did not shut down cleanly");
        Requests.request(store, Requests.WALK, "an operator asked");
        assertThat(Requests.pending(store)).as("one request per subject, the last reason standing")
                .singleElement().satisfies(request -> {
                    assertThat(request.subject()).isEqualTo(Requests.WALK);
                    assertThat(request.reason()).isEqualTo("an operator asked");
                    assertThat(request.at()).isAfter(Instant.EPOCH);
                });
        Requests.clear(store, Requests.WALK);
        assertThat(Requests.pending(store)).isEmpty();
        assertThatThrownBy(() -> Requests.request(store, "../escape", "no"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_request_from_a_scoped_store_reaches_the_installed_root_and_nowhere_without_one() throws IOException {
        assertThat(Requests.requestOnRoot(Requests.WALK, "before any driver installed a root"))
                .as("no root, no request - and no failure for the caller").isFalse();
        Requests.installRoot(store);
        assertThat(Requests.requestOnRoot(Requests.WALK, "an observer failed")).isTrue();
        assertThat(Requests.pending(store, Requests.WALK)).hasValueSatisfying(request ->
                assertThat(request.reason()).isEqualTo("an observer failed"));
    }

    @Test
    void the_running_marker_tells_an_unclean_boot_from_a_clean_one() throws IOException {
        assertThat(RunningMarker.boot(store, "node-a")).as("the first boot finds no marker").isFalse();
        assertThat(RunningMarker.running(store, "node-a")).isTrue();
        assertThat(RunningMarker.boot(store, "node-a")).as("a boot over a standing marker is an unclean one").isTrue();
        RunningMarker.clean(store, "node-a");
        assertThat(RunningMarker.running(store, "node-a")).isFalse();
        assertThat(RunningMarker.boot(store, "node-a")).as("after a clean shutdown the next boot is clean").isFalse();
        assertThat(RunningMarker.boot(store, "host.example/with:odd chars")).as("an id is reduced to a key segment")
                .isFalse();
    }

    @Test
    void a_request_not_before_a_moment_is_not_due_until_then() throws IOException {
        Instant later = Instant.now().plusSeconds(3600);
        Requests.request(store, "probe", "the pass failed; retried in an hour", later);
        Requests.Request request = Requests.pending(store, "probe").orElseThrow();
        assertThat(request.due(Instant.now())).as("a retry stands but is not due").isFalse();
        assertThat(request.due(later)).isTrue();
        assertThat(request.notBefore()).isEqualTo(later);
        Requests.request(store, "probe", "an operator asked now");
        assertThat(Requests.pending(store, "probe").orElseThrow().due(Instant.now()))
                .as("a plain request is due at once and supersedes the retry").isTrue();
    }
}
