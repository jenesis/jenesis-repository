package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.dependents.DeclaredDependents;
import build.jenesis.repository.dependents.DependentsQueryReader;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQuery.Declaration;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The declared tier: who names a package in a manifest, read from the dependencies the inventory recorded at publish
 * and kept apart from the resolved tier a vulnerability's blast radius is counted from. Driven over a real filesystem
 * store with the consolidated metadata installed, the way a deployment records a publish.
 */
class DeclaredDependentsTest {

    private static final UnaryOperator<String> DEFAULTS = _ -> null;

    /** Every pass a full one - what the cadence does every Nth pass. */
    private static final UnaryOperator<String> FULL = key -> "scan-full-every".equals(key) ? "1" : null;

    @TempDir
    Path root;

    private ArtifactStore store;

    private StoreRepositoryInventory inventory;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
        inventory = new StoreRepositoryInventory(store);
    }

    @Test
    void a_declaration_is_listed_with_its_requirement_and_never_joins_the_resolved_tier() throws Exception {
        publish("app", "1.0.0", declared("lodash", "^4.17.0"), declared("left-pad", "~1.3.0"));
        publish("lib", "2.0.0", declared("lodash", "4.17.21"));
        publish("other", "1.0.0");

        DeclaredDependents.Pass pass = new DeclaredDependents(store).pass(DEFAULTS);
        DependentsQuery reader = new DependentsQueryReader(store);

        assertThat(pass.full()).as("the first pass has no full pass to be incremental against").isTrue();
        assertThat(pass.changed()).as("two versions declare something; the third declares nothing").isEqualTo(2);
        assertThat(reader.declarations("lodash", null, 10).declarations()).containsExactly(
                new Declaration("npm", "app", "1.0.0", "^4.17.0"),
                new Declaration("npm", "lib", "2.0.0", "4.17.21"));
        assertThat(reader.declarations("left-pad", null, 10).declarations())
                .containsExactly(new Declaration("npm", "app", "1.0.0", "~1.3.0"));
        assertThat(reader.declarationsBuiltAt()).as("a full pass stamps the tier built").isPresent();
        // The resolved tier is what a vulnerability's count of affected artifacts is made from, and a requirement is
        // not a version anything was built against.
        assertThat(reader.reachable(List.of("lodash:4.17.21", "lodash:4.17.0"))).isEmpty();
        assertThat(reader.dependents("lodash")).isEmpty();
    }

    @Test
    void a_pass_over_an_unchanged_repository_writes_nothing() throws Exception {
        publish("app", "1.0.0", declared("lodash", "^4.17.0"));
        new DeclaredDependents(store).pass(DEFAULTS);

        assertThat(new DeclaredDependents(store).pass(FULL).changed())
                .as("every visit found its record equal to what the version declares").isZero();
    }

    @Test
    void a_version_published_since_the_last_full_pass_is_picked_up_by_an_incremental_one() throws Exception {
        publish("app", "1.0.0", declared("lodash", "^4.17.0"));
        new DeclaredDependents(store).pass(DEFAULTS);
        publish("app", "1.1.0", declared("lodash", "^4.18.0"));

        DeclaredDependents.Pass pass = new DeclaredDependents(store).pass(DEFAULTS);

        assertThat(pass.full()).isFalse();
        assertThat(new DependentsQueryReader(store).declarations("lodash", null, 10).declarations())
                .extracting(Declaration::version, Declaration::requirement)
                .containsExactly(tuple("1.0.0", "^4.17.0"), tuple("1.1.0", "^4.18.0"));
    }

    @Test
    void a_full_pass_takes_out_a_version_no_longer_published() throws Exception {
        publish("app", "1.0.0", declared("lodash", "^4.17.0"), declared("left-pad", "~1.3.0"));
        publish("lib", "2.0.0", declared("lodash", "4.17.21"));
        new DeclaredDependents(store).pass(DEFAULTS);
        store.delete(MetadataKey.version("npm", "app", "1.0.0"));

        DeclaredDependents.Pass pass = new DeclaredDependents(store).pass(FULL);
        DependentsQuery reader = new DependentsQueryReader(store);

        assertThat(pass.removed()).isEqualTo(1);
        assertThat(reader.declarations("lodash", null, 10).declarations())
                .containsExactly(new Declaration("npm", "lib", "2.0.0", "4.17.21"));
        assertThat(reader.declarations("left-pad", null, 10).declarations()).isEmpty();
    }

    @Test
    void a_changed_declaration_replaces_the_one_it_contributed_before() throws Exception {
        publish("app", "1.0.0", declared("lodash", "^4.17.0"));
        new DeclaredDependents(store).pass(DEFAULTS);
        // The section is replaced on a re-publish; the full pass re-decides the version against its record.
        publish("app", "1.0.0", declared("lodash", "^4.17.21"), declared("chalk", "^5"));

        new DeclaredDependents(store).pass(FULL);
        DependentsQuery reader = new DependentsQueryReader(store);

        assertThat(reader.declarations("lodash", null, 10).declarations())
                .containsExactly(new Declaration("npm", "app", "1.0.0", "^4.17.21"));
        assertThat(reader.declarations("chalk", null, 10).declarations()).hasSize(1);
    }

    @Test
    void the_declarations_of_a_package_page_by_cursor() throws Exception {
        for (int minor = 0; minor < 5; minor++) {
            publish("app", "1." + minor + ".0", declared("lodash", "^4"));
        }
        new DeclaredDependents(store).pass(DEFAULTS);
        DependentsQuery reader = new DependentsQueryReader(store);

        List<String> versions = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            DependentsQuery.DeclarationPage page = reader.declarations("lodash", cursor, 2);
            page.declarations().forEach(declaration -> versions.add(declaration.version()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(versions).containsExactly("1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0");
        assertThat(pages).as("five rows at two a page, the last page saying it is the last").isEqualTo(3);
    }

    @Test
    void a_tier_no_pass_has_built_reads_as_not_built() throws Exception {
        assertThat(new DependentsQueryReader(store).declarationsBuiltAt()).isEmpty();
    }

    private void publish(String name, String version, DependencySection.Declared... declared) throws IOException {
        inventory.recording("npm", name, version, false, Instant.now())
                .file("/package.tgz").dependencies(List.of(declared))
                .commit();
    }

    private static DependencySection.Declared declared(String coordinate, String requirement) {
        return new DependencySection.Declared(coordinate, requirement);
    }
}
