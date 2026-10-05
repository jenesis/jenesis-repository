package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureTask;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The closure pass resolves a release that has no closure, once: its closure lands in the version's document, a later
 * pass leaves it as resolved, and a repository that switched resolution off is left alone.
 */
class ClosureTaskTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final String APP = "/maven/org/acme/app/1.0/app-1.0.pom";

    @TempDir
    Path root;

    private ArtifactStore tenant;
    private ArtifactStore store;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() throws IOException {
        tenant = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default");
        store = tenant.scope("releases");
        metadata = MetadataProvider.installed().over(store);
        Publication publication = new Publication(store);
        publication.link(APP, publication.storeBlob(new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(store).record(APP, NOW);
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:missing", "1.0")), NOW));
    }

    @Test
    void a_release_without_a_closure_is_resolved_once() throws IOException {
        pass(null, NOW);
        Optional<ClosureSection.Closure> first = closure();
        assertThat(first).as("resolved on the first pass").isPresent();
        assertThat(first.get().cuts()).singleElement()
                .satisfies(cut -> assertThat(cut.coordinate()).isEqualTo("org.dep:missing"));

        pass(null, NOW.plus(Duration.ofHours(1)));
        assertThat(closure().orElseThrow().resolved()).as("a version is resolved once").isEqualTo(NOW);
    }

    @Test
    void a_repository_that_switched_resolution_off_is_left_alone() throws IOException {
        pass("false", NOW);
        assertThat(closure()).isEmpty();
    }

    @Test
    void a_group_resolves_through_the_repository_its_fallback_names() throws IOException {
        // The group publishes a release whose dependency only the repository its fallback names holds.
        ArtifactStore group = tenant.scope("group");
        String path = "/maven/org/acme/web/1.0/web-1.0.pom";
        Publication publication = new Publication(group);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(
                "<project/>".getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(group).record(path, NOW);
        MetadataStore groupMetadata = MetadataProvider.installed().over(group);
        groupMetadata.mutate("Maven", "org.acme:web", "1.0", DependencySection.TAG, DependencySection.record(path,
                List.of(new DependencySection.Declared("org.acme:app", "1.0")), NOW));

        pass("group", Map.of(RoutingSettingsContributor.KEY, "writable fallback releases"), null, NOW);

        ClosureSection.Closure closure = ClosureSection.closure(groupMetadata.section("Maven", "org.acme:web", "1.0",
                ClosureSection.TAG)).orElseThrow();
        assertThat(closure.components()).as("held by the repository the fallback names, and named by it")
                .containsExactly(new ClosureSection.Component("org.acme:app", "1.0", false, 1, "releases"));
        assertThat(closure.cuts()).as("and walked past it, into what app declares")
                .singleElement().satisfies(cut -> assertThat(cut.coordinate()).isEqualTo("org.dep:missing"));
    }

    @Test
    void a_release_carrying_a_bill_naming_its_closure_is_resolved_from_it() throws IOException {
        String jar = "/maven/org/acme/app/1.0/app-1.0.jar";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            out.write("""
                    {"bomFormat":"CycloneDX","specVersion":"1.5",
                     "metadata":{"component":{"bom-ref":"root","name":"app","version":"1.0"}},
                     "components":[{"bom-ref":"a","purl":"pkg:maven/org.dep/a@1.1","name":"a","version":"1.1"},
                                   {"bom-ref":"b","purl":"pkg:maven/org.dep/b@2.0","name":"b","version":"2.0"}],
                     "dependencies":[{"ref":"root","dependsOn":["a"]},{"ref":"a","dependsOn":["b"]}]}"""
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Publication publication = new Publication(store);
        publication.link(jar, publication.storeBlob(new ByteArrayInputStream(bytes.toByteArray())));
        new StoreRepositoryInventory(store).record(jar, NOW);

        pass(null, NOW);

        assertThat(closure().orElseThrow()).satisfies(closure -> {
            assertThat(closure.source()).as("the bill, not the declared dependencies")
                    .isEqualTo(ClosureSection.Source.BILL);
            assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate)
                    .containsExactlyInAnyOrder("org.dep:a", "org.dep:b");
        });
    }

    private Optional<ClosureSection.Closure> closure() throws IOException {
        return ClosureSection.closure(metadata.section("Maven", "org.acme:app", "1.0", ClosureSection.TAG));
    }

    private void pass(String setting, Instant now) throws IOException {
        pass("releases", Map.of(), setting, now);
    }

    private void pass(String repository, Map<String, String> config, String setting, Instant now) throws IOException {
        UnitFailures failures = new UnitFailures("the closure pass", "nothing");
        new ClosureTask(Duration.ofMinutes(5), QualityInspector.all()).repository(context(repository, config, setting,
                now, failures));
        failures.rethrow();
    }

    private RepositoryContext context(String repository, Map<String, String> config, String setting, Instant now,
                                      UnitFailures failures) {
        return new RepositoryContext() {
            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return repository;
            }

            @Override
            public Optional<RepositoryContext> repository(String name) {
                return Optional.of(context(name, Map.of(), setting, now, failures));
            }

            @Override
            public ArtifactStore store() {
                return tenant.scope(repository);
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> ClosureTask.SETTING.equals(key) ? setting : config.get(key);
            }

            @Override
            public UnitFailures failures(String work, String consequence) {
                return failures;
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
