package build.jenesis.repository.format.lifecycle.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle marks a resolver is told about: deprecated, yanked, and their absence.
 *
 * <p>What a mark means to a client differs by ecosystem - npm renders a deprecation as a warning, Cargo renders a
 * yank so a resolver skips the version unless already pinned - so the marks themselves have to be exact. These
 * assertions run against a temp directory in-process; the per-ecosystem legs still observe the same marks through
 * a real client, which is what proves the rendering.
 */
public class LifecycleMarkTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void an_unmarked_version_reads_as_unmarked_rather_than_as_anything_else() {
        // The common case, and the one a resolver sees for almost every version: absence is not a state.
        assertThat(readOrFail("org.acme:lib", "1.0")).isEmpty();
    }

    @Test
    void a_mark_reads_back_with_its_state_and_message() throws IOException {
        Lifecycle.mark(store, "org.acme:lib", "1.0",
                new Lifecycle.Flag(Lifecycle.State.DEPRECATED, "use 2.x"));

        assertThat(readOrFail("org.acme:lib", "1.0")).hasValueSatisfying(flag -> {
            assertThat(flag.state()).isEqualTo(Lifecycle.State.DEPRECATED);
            assertThat(flag.message()).isEqualTo("use 2.x");
        });
    }

    @Test
    void a_mark_is_per_version_and_does_not_leak_to_its_siblings() throws IOException {
        // A yank applies to one version. Marking the coordinate instead would withdraw a package wholesale, which
        // is a far larger act than the operator asked for and would be found by a customer rather than a test.
        Lifecycle.mark(store, "org.acme:lib", "1.0", new Lifecycle.Flag(Lifecycle.State.YANKED, "broken"));

        assertThat(readOrFail("org.acme:lib", "1.0")).isPresent();
        assertThat(readOrFail("org.acme:lib", "1.1")).as("the sibling is untouched").isEmpty();
        assertThat(readOrFail("org.acme:other", "1.0")).as("and so is another coordinate").isEmpty();
    }

    @Test
    void re_marking_a_version_replaces_the_mark_rather_than_accumulating() throws IOException {
        Lifecycle.mark(store, "org.acme:lib", "1.0",
                new Lifecycle.Flag(Lifecycle.State.DEPRECATED, "soft warning"));
        Lifecycle.mark(store, "org.acme:lib", "1.0",
                new Lifecycle.Flag(Lifecycle.State.YANKED, "actually withdrawn"));

        assertThat(readOrFail("org.acme:lib", "1.0")).hasValueSatisfying(flag -> {
            assertThat(flag.state()).as("an escalation replaces the earlier, softer mark")
                    .isEqualTo(Lifecycle.State.YANKED);
            assertThat(flag.message()).isEqualTo("actually withdrawn");
        });
    }

    @Test
    void clearing_is_idempotent_and_reports_whether_it_did_anything() throws IOException {
        Lifecycle.mark(store, "org.acme:lib", "1.0", new Lifecycle.Flag(Lifecycle.State.YANKED, "broken"));

        assertThat(Lifecycle.clear(store, "org.acme:lib", "1.0")).as("the first clear removes a mark").isTrue();
        assertThat(readOrFail("org.acme:lib", "1.0")).isEmpty();
        assertThat(Lifecycle.clear(store, "org.acme:lib", "1.0"))
                .as("a second clear is a clean no, not an error - an un-yank may be retried").isFalse();
    }

    @Test
    void the_versions_view_carries_every_marked_version_of_one_coordinate_in_order() throws IOException {
        Lifecycle.mark(store, "org.acme:lib", "1.2", new Lifecycle.Flag(Lifecycle.State.YANKED, ""));
        Lifecycle.mark(store, "org.acme:lib", "1.0", new Lifecycle.Flag(Lifecycle.State.DEPRECATED, ""));
        Lifecycle.mark(store, "org.acme:other", "9.9", new Lifecycle.Flag(Lifecycle.State.YANKED, ""));

        assertThat(Lifecycle.versions(store, "org.acme:lib")).containsOnlyKeys("1.0", "1.2");
    }

    @Test
    void a_state_name_parses_case_insensitively_and_an_unknown_one_is_refused() {
        // Operators type these, and the API takes them as text. An unrecognised name must not fall back to a state
        // - silently deprecating something because "depreciated" was misspelled is worse than refusing it.
        assertThat(Lifecycle.State.parse("deprecated")).contains(Lifecycle.State.DEPRECATED);
        assertThat(Lifecycle.State.parse("  YANKED ")).contains(Lifecycle.State.YANKED);
        assertThat(Lifecycle.State.parse("depreciated")).isEmpty();
        assertThat(Lifecycle.State.parse("")).isEmpty();
        assertThat(Lifecycle.State.parse(null)).isEmpty();
    }

    private Optional<Lifecycle.Flag> readOrFail(String coordinate, String version) {
        try {
            return Lifecycle.read(store, coordinate, version);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
