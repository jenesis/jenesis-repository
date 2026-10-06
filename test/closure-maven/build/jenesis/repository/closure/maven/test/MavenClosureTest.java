package build.jenesis.repository.closure.maven.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.closure.ClosureSource;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A Maven release resolved as Maven resolves it, from the POMs one repository holds: a version a parent's property and
 * managed dependency decide, a range taking the newest held version in it, and every dependency the repository does
 * not hold, or holds for review, recorded as a cut - with no request made for any of them.
 */
class MavenClosureTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

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
    void the_maven_closure_is_the_installed_resolver_for_maven() {
        assertThat(ClosureSource.serving("Maven")).as("discovered, after the carried bill and before the walk")
                .extracting(ClosureSource::name)
                .containsSubsequence("carried-bill", "maven-resolver", "declarations");
        assertThat(ClosureSource.serving("npm")).extracting(ClosureSource::name)
                .as("and asked about Maven alone").doesNotContain("maven-resolver");
    }

    @Test
    void a_parents_property_and_managed_version_decide_what_is_resolved() throws IOException {
        // The parent sets a property and manages lib's version by it; the release declares lib with no version.
        cached("org.acme", "parent", "1", """
                <packaging>pom</packaging>
                <properties><lib.version>1.2</lib.version></properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                </dependencies></dependencyManagement>""");
        release("org.acme", "app", "1.0", """
                <parent><groupId>org.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>lib</artifactId></dependency>
                  <dependency><groupId>org.dep</groupId><artifactId>ranged</artifactId><version>[1.0,2.0)</version></dependency>
                  <dependency><groupId>org.dep</groupId><artifactId>tests</artifactId><version>1.0</version><scope>test</scope></dependency>
                </dependencies>""");
        cached("org.dep", "lib", "1.1", "");
        cached("org.dep", "lib", "1.2", """
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>transitive</artifactId><version>3.0</version></dependency>
                </dependencies>""");
        cached("org.dep", "ranged", "1.0", "");
        cached("org.dep", "ranged", "1.5", "");
        cached("org.dep", "ranged", "2.0", "");

        ClosureSection.Closure closure = resolve("org.acme:app", "1.0");

        assertThat(closure.components()).extracting(ClosureSection.Component::coordinate,
                        ClosureSection.Component::version, ClosureSection.Component::depth)
                .as("the managed version, the newest held in the range, and nothing a consumer does not pull in")
                .containsExactly(tuple("org.dep:lib", "1.2", 1), tuple("org.dep:ranged", "1.5", 1));
        assertThat(closure.cuts()).singleElement().satisfies(cut -> {
            assertThat(cut.coordinate()).isEqualTo("org.dep:transitive");
            assertThat(cut.reason()).isEqualTo("not held by this repository");
        });
        assertThat(closure.status()).isEqualTo(ClosureSection.Status.PARTIAL);
    }

    @Test
    void a_transitive_dependency_records_the_dependency_whose_pom_brought_it_in() throws IOException {
        release("org.acme", "app", "1.0", """
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                </dependencies>""");
        cached("org.dep", "lib", "1.0", """
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>transitive</artifactId><version>3.0</version></dependency>
                </dependencies>""");
        cached("org.dep", "transitive", "3.0", "");

        ClosureSection.Closure closure = resolve("org.acme:app", "1.0");

        assertThat(closure.components()).extracting(ClosureSection.Component::coordinate,
                        ClosureSection.Component::depth, ClosureSection.Component::viaCoordinate,
                        ClosureSection.Component::viaVersion)
                .containsExactly(tuple("org.dep:lib", 1, "", ""), tuple("org.dep:transitive", 2, "org.dep:lib", "1.0"));
    }

    @Test
    void a_dependency_held_for_review_is_a_cut_that_says_so() throws IOException {
        release("org.acme", "app", "1.0", """
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>held</artifactId><version>1.0</version></dependency>
                </dependencies>""");
        cached("org.dep", "held", "1.0", "");
        String path = "/maven/org/dep/held/1.0/held-1.0.pom";
        publication.link("/quarantine" + path, publication.blob(path).orElseThrow());
        store.delete("publish" + path);

        assertThat(resolve("org.acme:app", "1.0").cuts()).singleElement()
                .satisfies(cut -> assertThat(cut.reason()).isEqualTo("held for review"));
    }

    @Test
    void a_repository_a_pom_declares_is_never_asked() throws IOException {
        release("org.acme", "app", "1.0", """
                <repositories><repository><id>elsewhere</id><url>http://127.0.0.1:9/never</url></repository></repositories>
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>remote-only</artifactId><version>1.0</version></dependency>
                </dependencies>""");

        ClosureSection.Closure closure = resolve("org.acme:app", "1.0");

        assertThat(closure.cuts()).singleElement().satisfies(cut -> {
            assertThat(cut.coordinate()).isEqualTo("org.dep:remote-only");
            assertThat(cut.reason()).isEqualTo("not held by this repository");
        });
    }

    @Test
    void a_release_without_a_pom_is_left_to_the_walk_by_declarations() throws IOException {
        publication.link("/maven/org/acme/jar/1.0/jar-1.0.jar",
                publication.storeBlob(new ByteArrayInputStream(new byte[]{1})));
        assertThat(maven().resolve(ClosureWalk.of(store), "Maven", "org.acme:jar", "1.0", NOW)).isEmpty();
    }

    @Test
    void a_group_reads_poms_and_versions_through_the_repository_its_fallback_names() throws IOException {
        // The group publishes the release and holds lib 1.0; its parent and lib's newer versions are held only by the
        // repository its fallback names, so the parent's managed range sees the versions of both.
        ArtifactStore group = tenant.scope("group");
        cached("org.acme", "parent", "1", """
                <packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>lib</artifactId><version>[1.0,3.0)</version></dependency>
                </dependencies></dependencyManagement>""");
        cached("org.dep", "lib", "2.0", "");
        cached("org.dep", "lib", "3.0", "");
        new StoreRepositoryInventory(group).record(link(group, "org.dep", "lib", "1.0", ""), NOW);
        new StoreRepositoryInventory(group).record(link(group, "org.acme", "app", "1.0", """
                <parent><groupId>org.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>
                <dependencies>
                  <dependency><groupId>org.dep</groupId><artifactId>lib</artifactId></dependency>
                  <dependency><groupId>org.dep</groupId><artifactId>gone</artifactId><version>1.0</version></dependency>
                </dependencies>"""), NOW);

        ClosureSection.Closure closure = maven().resolve(new ClosureWalk(List.of(
                new ClosureWalk.Member("group", group), new ClosureWalk.Member("releases", store))),
                "Maven", "org.acme:app", "1.0", NOW).orElseThrow();

        assertThat(closure.components()).as("the newest in the range across the walk, named by the repository holding it")
                .containsExactly(new ClosureSection.Component("org.dep:lib", "2.0", true, 1, "releases"));
        assertThat(closure.cuts()).singleElement().satisfies(cut -> {
            assertThat(cut.coordinate()).isEqualTo("org.dep:gone");
            assertThat(cut.reason()).isEqualTo("not held by this repository or a repository its fallbacks name");
        });

        cached("org.dep", "gone", "1.0", "");
        assertThat(maven().resolve(new ClosureWalk(List.of(
                new ClosureWalk.Member("group", group), new ClosureWalk.Member("releases", store))),
                "Maven", "org.acme:app", "1.0", NOW).orElseThrow().components())
                .as("a component the fallback's repository holds is named by it")
                .contains(new ClosureSection.Component("org.dep:gone", "1.0", true, 1, "releases"));
    }

    /** The Maven Resolver as the closure pass finds it: the installed source of that name. */
    private static ClosureSource maven() {
        return ClosureSource.installed().stream().filter(source -> "maven-resolver".equals(source.name()))
                .findFirst().orElseThrow();
    }

    private ClosureSection.Closure resolve(String coordinate, String version) throws IOException {
        return maven().resolve(ClosureWalk.of(store), "Maven", coordinate, version, NOW)
                .orElseThrow();
    }

    private void release(String group, String artifact, String version, String body) throws IOException {
        String path = link(group, artifact, version, body);
        inventory.record(path, NOW);
    }

    private void cached(String group, String artifact, String version, String body) throws IOException {
        link(group, artifact, version, body);
        inventory.cache("Maven", group + ":" + artifact, version, "https://repo.example/maven2/", NOW);
    }

    private String link(String group, String artifact, String version, String body) throws IOException {
        return link(store, group, artifact, version, body);
    }

    private static String link(ArtifactStore store, String group, String artifact, String version, String body)
            throws IOException {
        Publication publication = new Publication(store);
        String path = "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom";
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version>" + body + "</project>";
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8))));
        return path;
    }
}
