package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureResolver;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A release's closure as one repository holds it: its own releases and the copies it cached, the newest held version
 * a requirement admits, a cached copy's declarations read off its POM, and every dependency that does not resolve
 * recorded with its reason rather than ending the walk.
 */
class ClosureResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final String UPSTREAM = "https://repo.example/maven2/";

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreRepositoryInventory inventory;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("releases");
        inventory = new StoreRepositoryInventory(store);
        publication = new Publication(store);
    }

    @Test
    void a_closure_walks_releases_and_cached_copies_and_records_what_does_not_resolve() throws IOException {
        // The release resolved: it asks for a at exactly 1.1, for b at whatever is held, and for something never held.
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", "1.1"),
                new DependencySection.Declared("org.dep:b", ""),
                new DependencySection.Declared("org.dep:missing", "1.0")));
        release("org.dep", "a", "1.0", List.of());
        // a 1.1 is a release of this repository, declaring c at 2.0.
        release("org.dep", "a", "1.1", List.of(new DependencySection.Declared("org.dep:c", "2.0")));
        // b is held as two cached copies; the newer one's POM declares d, which nothing holds.
        cached("org.dep", "b", "1.0", pom("org.dep", "b", "1.0", ""));
        cached("org.dep", "b", "2.0", pom("org.dep", "b", "2.0",
                "<dependency><groupId>org.dep</groupId><artifactId>d</artifactId><version>3.0</version></dependency>"));
        // c 2.0 is cached and held for review.
        cached("org.dep", "c", "2.0", pom("org.dep", "c", "2.0", ""));
        String held = "/maven/org/dep/c/2.0/c-2.0.pom";
        publication.link("/quarantine" + held, publication.blob(held).orElseThrow());

        ClosureSection.Closure closure = new ClosureResolver(store, QualityInspector.all())
                .resolve("Maven", "org.acme:app", "1.0", NOW);

        assertThat(closure.components()).containsExactly(
                new ClosureSection.Component("org.dep:a", "1.1", false, 1),
                new ClosureSection.Component("org.dep:b", "2.0", true, 1));
        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactlyInAnyOrder(
                        tuple("org.dep:missing", "not held by this repository"),
                        tuple("org.dep:c", "every held version it admits is held for review"),
                        tuple("org.dep:d", "not held by this repository"));
        assertThat(closure.status()).as("a closure with a cut is partial").isEqualTo(ClosureSection.Status.PARTIAL);
    }

    @Test
    void a_requirement_its_grammar_cannot_read_is_a_cut_not_a_guess() throws IOException {
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", "${a.version}")));
        release("org.dep", "a", "1.0", List.of());

        ClosureSection.Closure closure = new ClosureResolver(store, QualityInspector.all())
                .resolve("Maven", "org.acme:app", "1.0", NOW);

        assertThat(closure.components()).isEmpty();
        assertThat(closure.cuts()).singleElement()
                .satisfies(cut -> assertThat(cut.reason()).isEqualTo("the requirement could not be evaluated"));
    }

    @Test
    void a_version_whose_every_dependency_resolves_is_resolved_whole() throws IOException {
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", "")));
        release("org.dep", "a", "1.0", List.of());
        release("org.dep", "a", "1.10", List.of());
        release("org.dep", "a", "1.9", List.of());

        ClosureSection.Closure closure = new ClosureResolver(store, QualityInspector.all())
                .resolve("Maven", "org.acme:app", "1.0", NOW);

        assertThat(closure.status()).isEqualTo(ClosureSection.Status.RESOLVED);
        assertThat(closure.components()).as("the newest held version, ordered as versions are")
                .containsExactly(new ClosureSection.Component("org.dep:a", "1.10", false, 1));
    }

    @Test
    void a_range_takes_the_newest_held_version_it_admits() throws IOException {
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", "[1.0,2.0)")));
        release("org.dep", "a", "1.2", List.of());
        release("org.dep", "a", "1.10", List.of());
        release("org.dep", "a", "2.0", List.of());

        assertThat(new ClosureResolver(store, QualityInspector.all()).resolve("Maven", "org.acme:app", "1.0", NOW)
                .components()).as("the newest held version in the range, not the newest held")
                .containsExactly(new ClosureSection.Component("org.dep:a", "1.10", false, 1));
    }

    @Test
    void a_version_declaring_nothing_readable_is_undeclared_rather_than_empty() throws IOException {
        String path = "/maven/org/acme/blob/1.0/blob-1.0.bin";
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        inventory.record(path, NOW);
        release("org.acme", "lean", "1.0", List.of());

        assertThat(new ClosureResolver(store, QualityInspector.all()).resolve("Maven", "org.acme:blob", "1.0", NOW)
                .status()).as("nothing recorded and nothing readable").isEqualTo(ClosureSection.Status.UNDECLARED);
        assertThat(new ClosureResolver(store, QualityInspector.all()).resolve("Maven", "org.acme:lean", "1.0", NOW)
                .status()).as("a manifest that declared none resolves to nothing")
                .isEqualTo(ClosureSection.Status.RESOLVED);
    }

    private void release(String group, String artifact, String version, List<DependencySection.Declared> declared)
            throws IOException {
        String path = "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom";
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(pom(group, artifact, version, ""))));
        inventory.record(path, NOW);
        MetadataProvider.installed().over(store).mutate("Maven", group + ":" + artifact, version,
                DependencySection.TAG, DependencySection.record(path, declared, NOW));
    }

    private void cached(String group, String artifact, String version, byte[] pom) throws IOException {
        String path = "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom";
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(pom)));
        inventory.cache("Maven", group + ":" + artifact, version, UPSTREAM, NOW);
    }

    private static byte[] pom(String group, String artifact, String version, String dependencies) {
        return ("<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><dependencies>" + dependencies
                + "</dependencies></project>").getBytes(StandardCharsets.UTF_8);
    }
}
