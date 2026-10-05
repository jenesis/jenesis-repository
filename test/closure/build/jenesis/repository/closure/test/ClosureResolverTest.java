package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureResolver;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.Section;
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

    private ArtifactStore tenant;
    private ArtifactStore store;
    private StoreRepositoryInventory inventory;
    private Publication publication;

    @BeforeEach
    void setUp() {
        tenant = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default");
        store = tenant.scope("releases");
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
                new ClosureSection.Component("org.dep:a", "1.1", false, 1, ""),
                new ClosureSection.Component("org.dep:b", "2.0", true, 1, ""));
        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactlyInAnyOrder(
                        tuple("org.dep:missing", "not held by this repository"),
                        tuple("org.dep:c", "every held version it admits is held for review"),
                        tuple("org.dep:d", "not held by this repository"));
        assertThat(closure.status()).as("a closure with a cut is partial").isEqualTo(ClosureSection.Status.PARTIAL);
    }

    @Test
    void a_copy_held_at_its_fill_is_a_hold_although_no_holding_records_it() throws IOException {
        // A proxied copy the screen held as it was fetched is no holding until a reviewer releases it: what records it
        // is the hold's own subject, and the review pointer at its path.
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:held", "1.0"),
                new DependencySection.Declared("org.dep:ranged", "[1.0,2.0)"),
                new DependencySection.Declared("org.dep:released", "1.0")));
        hold("org.dep", "held", "1.0");
        hold("org.dep", "ranged", "1.5");
        // A row a crash left behind once its hold was released holds nothing.
        String released = "/maven/org/dep/released/1.0/released-1.0.pom";
        HeldSubjects.record(store, released, "Maven", "org.dep:released", "1.0");

        ClosureSection.Closure closure = new ClosureResolver(store, QualityInspector.all())
                .resolve("Maven", "org.acme:app", "1.0", NOW);

        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactlyInAnyOrder(
                        tuple("org.dep:held", "every held version it admits is held for review"),
                        tuple("org.dep:ranged", "every held version it admits is held for review"),
                        tuple("org.dep:released", "not held by this repository"));
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
                .containsExactly(new ClosureSection.Component("org.dep:a", "1.10", false, 1, ""));
    }

    @Test
    void a_range_takes_the_newest_held_version_it_admits() throws IOException {
        release("org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", "[1.0,2.0)")));
        release("org.dep", "a", "1.2", List.of());
        release("org.dep", "a", "1.10", List.of());
        release("org.dep", "a", "2.0", List.of());

        assertThat(new ClosureResolver(store, QualityInspector.all()).resolve("Maven", "org.acme:app", "1.0", NOW)
                .components()).as("the newest held version in the range, not the newest held")
                .containsExactly(new ClosureSection.Component("org.dep:a", "1.10", false, 1, ""));
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

    @Test
    void a_dependency_a_fallback_holds_resolves_through_it_and_names_it() throws IOException {
        // The group publishes the release; what it declares is held only by the repository its fallback names, which
        // also holds an older version of a the group itself holds a newer one of.
        ArtifactStore group = tenant.scope("group");
        release(group, "org.acme", "app", "1.0", List.of(new DependencySection.Declared("org.dep:a", ""),
                new DependencySection.Declared("org.dep:b", ""),
                new DependencySection.Declared("org.dep:missing", "")));
        release(group, "org.dep", "a", "2.0", List.of());
        release("org.dep", "a", "1.0", List.of());
        cached("org.dep", "b", "1.0", pom("org.dep", "b", "1.0", ""));

        ClosureSection.Closure closure = new ClosureResolver(new ClosureWalk(List.of(
                new ClosureWalk.Member("group", group), new ClosureWalk.Member("releases", store))),
                QualityInspector.all()).resolve("Maven", "org.acme:app", "1.0", NOW);

        assertThat(closure.components()).as("the newest across the walk, each named by the repository holding it")
                .containsExactly(new ClosureSection.Component("org.dep:a", "2.0", false, 1, ""),
                        new ClosureSection.Component("org.dep:b", "1.0", true, 1, "releases"));
        assertThat(closure.cuts()).singleElement().satisfies(cut -> assertThat(cut.reason())
                .isEqualTo("not held by this repository or a repository its fallbacks name"));
        assertThat(ClosureSection.closure(Optional.of(roundTrip(closure))).orElseThrow().components())
                .as("the holding repository survives the document").isEqualTo(closure.components());
    }

    private Section roundTrip(ClosureSection.Closure closure) {
        return ClosureSection.record(closure).apply(Optional.empty());
    }

    private void release(String group, String artifact, String version, List<DependencySection.Declared> declared)
            throws IOException {
        release(store, group, artifact, version, declared);
    }

    private static void release(ArtifactStore store, String group, String artifact, String version,
                                List<DependencySection.Declared> declared) throws IOException {
        Publication publication = new Publication(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        String path = "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom";
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(pom(group, artifact, version, ""))));
        inventory.record(path, NOW);
        MetadataProvider.installed().over(store).mutate("Maven", group + ":" + artifact, version,
                DependencySection.TAG, DependencySection.record(path, declared, NOW));
    }

    /** A copy held for review as the proxy holds one at its fill: its subject recorded and its review pointer linked,
     *  and no holding. */
    private void hold(String group, String artifact, String version) throws IOException {
        String path = "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom";
        HeldSubjects.hold(publication, store, path,
                publication.storeBlob(new ByteArrayInputStream(pom(group, artifact, version, ""))), "Maven",
                group + ":" + artifact, version);
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
