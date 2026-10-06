package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a version's document says of its closure, as the console and the API both answer it: a release not yet resolved
 * is pending rather than empty, a resolved one carries its closure, a cached copy has none of its own, and a version
 * the repository does not hold has no answer at all.
 */
class ClosureAnswerTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    private StoreRepositoryInventory inventory;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("releases");
        inventory = new StoreRepositoryInventory(store);
        metadata = MetadataProvider.installed().over(store);
    }

    @Test
    void a_release_is_pending_until_its_closure_is_resolved_and_then_carries_it() throws IOException {
        inventory.record("Maven", "org.acme:app", "1.0", NOW);
        assertThat(answer("org.acme:app", "1.0")).hasValueSatisfying(answer -> {
            assertThat(answer.state()).isEqualTo(ClosureSection.State.PENDING);
            assertThat(answer.closure()).isNull();
        });

        ClosureSection.Closure partial = new ClosureSection.Closure(ClosureSection.Status.PARTIAL,
                List.of(new ClosureSection.Component("org.dep:a", "1.1", true, 1, "releases")),
                List.of(new ClosureSection.Cut("org.dep:gone", "2.0", "not held by this repository")), false, NOW,
                ClosureSource.Kind.DECLARATIONS, "declarations");
        metadata.mutate("Maven", "org.acme:app", "1.0", ClosureSection.TAG, ClosureSection.record(partial));

        assertThat(answer("org.acme:app", "1.0")).hasValueSatisfying(answer -> {
            assertThat(answer.state()).isEqualTo(ClosureSection.State.PARTIAL);
            assertThat(answer.closure().components()).isEqualTo(partial.components());
            assertThat(answer.closure().cuts()).isEqualTo(partial.cuts());
        });
    }

    @Test
    void a_release_declaring_nothing_readable_is_undeclared() throws IOException {
        inventory.record("Maven", "org.acme:blob", "1.0", NOW);
        metadata.mutate("Maven", "org.acme:blob", "1.0", ClosureSection.TAG, ClosureSection.record(
                new ClosureSection.Closure(ClosureSection.Status.UNDECLARED, List.of(), List.of(), false, NOW,
                        ClosureSource.Kind.DECLARATIONS, "declarations")));

        assertThat(answer("org.acme:blob", "1.0")).map(ClosureSection.Answer::state)
                .hasValue(ClosureSection.State.UNDECLARED);
    }

    @Test
    void a_cached_copy_has_no_closure_of_its_own() throws IOException {
        inventory.cache("Maven", "org.dep:b", "2.0", "https://repo.example/maven2/", NOW);

        assertThat(answer("org.dep:b", "2.0")).hasValueSatisfying(answer -> {
            assertThat(answer.state()).isEqualTo(ClosureSection.State.CACHED);
            assertThat(answer.closure()).isNull();
        });
    }

    @Test
    void a_version_the_repository_does_not_hold_has_no_answer() throws IOException {
        assertThat(answer("org.acme:never", "9.9")).isEmpty();
    }

    private Optional<ClosureSection.Answer> answer(String coordinate, String version) throws IOException {
        return metadata.read("Maven", coordinate, version).flatMap(ClosureSection::answer);
    }
}
