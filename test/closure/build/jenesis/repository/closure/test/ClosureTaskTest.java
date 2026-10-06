package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureSource;
import build.jenesis.repository.closure.ClosureTask;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.closure.ExposureSection;
import build.jenesis.repository.closure.ReliedOn;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.inventory.ChangedVersions;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.TenantContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

        // What the group's release relies on carries a finding where the fallback's repository holds it.
        FindingsProvider.installed().orElseThrow().over(store).record("Maven", "org.acme:app", "1.0", Finding.of(
                "CVE-2026-0002", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "recorded", NOW));

        pass("group", Map.of(RoutingSettingsContributor.KEY, "writable fallback releases"), null, NOW);

        ClosureSection.Closure closure = ClosureSection.closure(groupMetadata.section("Maven", "org.acme:web", "1.0",
                ClosureSection.TAG)).orElseThrow();
        assertThat(closure.components()).as("held by the repository the fallback names, and named by it")
                .containsExactly(new ClosureSection.Component("org.acme:app", "1.0", false, 1, "releases"));
        assertThat(closure.cuts()).as("and walked past it, into what app declares")
                .singleElement().satisfies(cut -> assertThat(cut.coordinate()).isEqualTo("org.dep:missing"));
        assertThat(ExposureSection.exposure(groupMetadata.section("Maven", "org.acme:web", "1.0", ExposureSection.TAG))
                .orElseThrow().reached()).as("the finding, read where the fallback's repository holds the copy")
                .containsExactly(new ExposureSection.Reached("org.acme:app", "1.0", "releases", false, 1, "HIGH"));
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
            assertThat(closure.kind()).as("the bill, not the declared dependencies")
                    .isEqualTo(ClosureSource.Kind.BILL);
            assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate)
                    .containsExactlyInAnyOrder("org.dep:a", "org.dep:b");
        });
    }

    /** A source of {@code kind} serving {@code ecosystems} that records being asked and answers {@code answer}. */
    private static ClosureSource source(String name, ClosureSource.Kind kind, Set<String> ecosystems,
                                        Optional<ClosureSection.Closure> answer, List<String> asked) {
        return new ClosureSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> ecosystems() {
                return ecosystems;
            }

            @Override
            public Kind kind() {
                return kind;
            }

            @Override
            public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                            String version, Instant now) {
                asked.add(name);
                return answer;
            }
        };
    }

    @Test
    void the_first_source_serving_the_ecosystem_that_answers_is_the_closure() throws IOException {
        List<String> asked = new ArrayList<>();
        ClosureSection.Closure resolved = new ClosureSection.Closure(ClosureSection.Status.RESOLVED, List.of(),
                List.of(), false, NOW, ClosureSource.Kind.RESOLVER, "resolver");
        UnitFailures failures = new UnitFailures("the closure pass", "nothing");
        new ClosureTask(Duration.ofMinutes(5), List.of(
                source("npm-only", ClosureSource.Kind.BILL, Set.of("npm"), Optional.of(resolved), asked),
                source("no-bill", ClosureSource.Kind.BILL, Set.of("Maven"), Optional.empty(), asked),
                source("resolver", ClosureSource.Kind.RESOLVER, Set.of("Maven"), Optional.of(resolved), asked),
                source("walk", ClosureSource.Kind.DECLARATIONS, Set.of("Maven"), Optional.of(resolved), asked)))
                .repository(context("releases", Map.of(), null, NOW, failures));
        failures.rethrow();

        assertThat(asked).as("a source not serving Maven is never asked, and none after the first answer")
                .containsExactly("no-bill", "resolver");
        assertThat(closure().orElseThrow().source()).isEqualTo("resolver");
    }

    @Test
    void a_release_inherits_the_state_of_what_its_closure_reaches_and_follows_it_on_the_next_full_pass()
            throws IOException {
        // app 2.0 asks for a cached copy carrying a critical finding, a copy the screen held at its fill, and a clean
        // cached copy, which it reaches and inherits nothing from.
        String path = "/maven/org/acme/app/2.0/app-2.0.pom";
        Publication publication = new Publication(store);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream("<project/>".getBytes(
                StandardCharsets.UTF_8))));
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record(path, NOW);
        metadata.mutate("Maven", "org.acme:app", "2.0", DependencySection.TAG, DependencySection.record(path,
                List.of(new DependencySection.Declared("org.dep:vulnerable", "1.0"),
                        new DependencySection.Declared("org.dep:held", "1.0"),
                        new DependencySection.Declared("org.dep:clean", "1.0")), NOW));
        cached("vulnerable", "1.0");
        cached("clean", "1.0");
        String held = "/maven/org/dep/held/1.0/held-1.0.pom";
        HeldSubjects.hold(publication, store, held, publication.storeBlob(new ByteArrayInputStream(
                "<project/>".getBytes(StandardCharsets.UTF_8))), "Maven", "org.dep:held", "1.0");
        Findings ledger = FindingsProvider.installed().orElseThrow().over(store);
        ledger.record("Maven", "org.dep:vulnerable", "1.0", Finding.of("CVE-2026-0001", "osv",
                Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL, "a recorded advisory", NOW));

        pass(null, NOW);

        ExposureSection.Exposure first = exposure("2.0").orElseThrow();
        assertThat(first.reached()).as("the cached copy's finding and the held copy's hold, inherited")
                .containsExactlyInAnyOrder(
                        new ExposureSection.Reached("org.dep:vulnerable", "1.0", "", false, 1, "CRITICAL"),
                        new ExposureSection.Reached("org.dep:held", "1.0", "", true, 0, ""));
        assertThat(first.examined()).as("the clean copy was looked at too").isEqualTo(3);

        // The finding is superseded: the next full pass follows the copy, with no feed asked and no closure re-resolved.
        ledger.supersede("Maven", "org.dep:vulnerable", "1.0", "osv", "CVE-2026-0001", "withdrawn");
        pass("releases", Map.of(IncrementalPasses.FULL_EVERY, "1"), null, NOW.plus(Duration.ofHours(1)));

        assertThat(exposure("2.0").orElseThrow().reached()).as("only the hold is inherited now")
                .containsExactly(new ExposureSection.Reached("org.dep:held", "1.0", "", true, 0, ""));
        assertThat(ClosureSection.closure(metadata.section("Maven", "org.acme:app", "2.0", ClosureSection.TAG))
                .orElseThrow().resolved()).as("the closure itself is resolved once").isEqualTo(NOW);
    }

    @Test
    void a_cached_copy_names_every_release_relying_on_it_with_the_path_it_is_reached_along() throws IOException {
        // app 1.0, released here, relies on a cached lib; web 1.0, published to a group whose fallback is this
        // repository, relies on app and so on lib through it.
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:lib", "1.0")), NOW));
        cached("lib", "1.0");
        web("org.acme:app");

        pass(null, NOW);
        pass("group", Map.of(RoutingSettingsContributor.KEY, "writable fallback releases"), null, NOW);

        assertThat(reliedOn("org.dep:lib", "1.0", _ -> true).dependents()).as("both, each with its path")
                .containsExactlyInAnyOrder(
                        new ReliedOn.Dependent("releases", "Maven", "org.acme:app", "1.0",
                                List.of(new ClosureSection.Hop("org.dep:lib", "1.0")), false, false),
                        new ReliedOn.Dependent("group", "Maven", "org.acme:web", "1.0",
                                List.of(new ClosureSection.Hop("org.acme:app", "1.0"),
                                        new ClosureSection.Hop("org.dep:lib", "1.0")), false, false));
        assertThat(reliedOn("org.dep:lib", "1.0", "releases"::equals).dependents())
                .as("a repository the caller may not read is left out")
                .extracting(ReliedOn.Dependent::repository).containsExactly("releases");
        assertThat(ExposureSection.exposure(MetadataProvider.installed().over(tenant.scope("group"))
                .section("Maven", "org.acme:web", "1.0", ExposureSection.TAG)).orElseThrow().reached())
                .as("nothing it reaches is held or vulnerable").isEmpty();
    }

    @Test
    void a_held_copy_names_the_release_whose_closure_stopped_at_it() throws IOException {
        String held = "/maven/org/dep/held/1.0/held-1.0.pom";
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, held, publication.storeBlob(new ByteArrayInputStream(
                "<project/>".getBytes(StandardCharsets.UTF_8))), "Maven", "org.dep:held", "1.0");
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:held", "1.0")), NOW));

        pass(null, NOW);

        assertThat(reliedOn("org.dep:held", "1.0", _ -> true).dependents()).singleElement()
                .isEqualTo(new ReliedOn.Dependent("releases", "Maven", "org.acme:app", "1.0",
                        List.of(new ClosureSection.Hop("org.dep:held", "1.0")), true, false));
    }

    @Test
    void a_vulnerable_copy_reached_through_a_dependency_is_inherited_with_its_path() throws IOException {
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:lib", "1.0")), NOW));
        cached("lib", "1.0");
        FindingsProvider.installed().orElseThrow().over(store).record("Maven", "org.dep:lib", "1.0", Finding.of(
                "CVE-2026-0003", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "recorded", NOW));
        web("org.acme:app");

        pass("group", Map.of(RoutingSettingsContributor.KEY, "writable fallback releases"), null, NOW);

        assertThat(ExposureSection.exposure(MetadataProvider.installed().over(tenant.scope("group"))
                .section("Maven", "org.acme:web", "1.0", ExposureSection.TAG)).orElseThrow().reached())
                .containsExactly(new ExposureSection.Reached("org.dep:lib", "1.0", "releases", false, 1, "HIGH",
                        List.of(new ClosureSection.Hop("org.acme:app", "1.0"),
                                new ClosureSection.Hop("org.dep:lib", "1.0"))));
    }

    @Test
    void a_row_goes_once_its_dependent_no_longer_relies_on_what_it_names() throws IOException {
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:lib", "1.0")), NOW));
        cached("lib", "1.0");
        web("org.acme:app");
        pass(null, NOW);
        pass("group", Map.of(RoutingSettingsContributor.KEY, "writable fallback releases"), null, NOW);
        assertThat(rows()).as("a row per release relying on lib, and web's on app").hasSize(3);

        // web waits for a closure again: its rows stay, since its pass writes them before it records one.
        MetadataStore group = MetadataProvider.installed().over(tenant.scope("group"));
        group.mutate("Maven", "org.acme:web", "1.0", ClosureSection.TAG, _ -> null);
        Map<String, String> full = Map.of(IncrementalPasses.FULL_EVERY, "1");
        pass("releases", full, "false", NOW.plus(Duration.ofHours(1)));
        assertThat(rows()).as("a dependent waiting for its closure keeps its rows").hasSize(3);

        // app's closure no longer reaches lib, and web is no longer published.
        metadata.mutate("Maven", "org.acme:app", "1.0", ClosureSection.TAG, ClosureSection.record(
                new ClosureSection.Closure(ClosureSection.Status.RESOLVED, List.of(), List.of(), false, NOW,
                        ClosureSource.Kind.DECLARATIONS, "test")));
        group.mutate("Maven", "org.acme:web", "1.0", PublishedSection.TAG, _ -> null);
        pass("releases", full, "false", NOW.plus(Duration.ofHours(2)));
        assertThat(rows()).as("rows no closure names go, even where the repository resolves none itself").isEmpty();
    }

    @Test
    void a_closure_without_its_rows_is_indexed_on_the_next_full_pass() throws IOException {
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:lib", "1.0")), NOW));
        cached("lib", "1.0");
        pass(null, NOW);
        for (String row : rows()) {
            store.delete(row);   // a closure resolved before the index, or whose rows a crash lost
        }

        pass("releases", Map.of(), null, NOW.plus(Duration.ofMinutes(10)));
        assertThat(rows()).as("an incremental pass that finds the closure recorded writes nothing").isEmpty();

        pass("releases", Map.of(IncrementalPasses.FULL_EVERY, "1"), null, NOW.plus(Duration.ofHours(1)));
        assertThat(reliedOn("org.dep:lib", "1.0", _ -> true).dependents())
                .extracting(ReliedOn.Dependent::coordinate).containsExactly("org.acme:app");
    }

    @Test
    void a_new_finding_on_a_copy_reaches_a_release_relying_on_it_before_its_full_pass() throws IOException {
        metadata.mutate("Maven", "org.acme:app", "1.0", DependencySection.TAG, DependencySection.record(APP,
                List.of(new DependencySection.Declared("org.dep:lib", "1.0")), NOW));
        cached("lib", "1.0");
        web("org.acme:app", NOW.minus(Duration.ofDays(1)));
        Map<String, String> group = Map.of(RoutingSettingsContributor.KEY, "writable fallback releases");
        pass(null, NOW);
        pass("group", group, null, NOW);
        assertThat(webExposure()).as("nothing it reaches is vulnerable yet").isEmpty();

        // The copy is found vulnerable; neither repository's next pass is a full one, and web is not a recent publish.
        FindingsProvider.installed().orElseThrow().over(store).record("Maven", "org.dep:lib", "1.0", Finding.of(
                "CVE-2026-0004", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "recorded", NOW));
        pass("releases", Map.of(), null, NOW.plus(Duration.ofHours(1)));
        pass("group", group, null, NOW.plus(Duration.ofHours(1)));

        assertThat(webExposure()).as("inherited through the index, along its path, with no feed asked")
                .containsExactly(new ExposureSection.Reached("org.dep:lib", "1.0", "releases", false, 1, "HIGH",
                        List.of(new ClosureSection.Hop("org.acme:app", "1.0"),
                                new ClosureSection.Hop("org.dep:lib", "1.0"))));
        assertThat(store.isEmpty(ChangedVersions.ROOT)).as("the change was taken up").isTrue();
        assertThat(tenant.scope("group").isEmpty(ReliedOn.STALE)).as("and the request it made").isTrue();
    }

    @Test
    void a_package_a_bill_names_in_another_ecosystem_is_relied_on_through_any_copy_the_tenant_holds()
            throws IOException {
        ArtifactStore npm = tenant.scope("npm-proxy");
        new StoreRepositoryInventory(npm).cache("npm", "left-pad", "1.3.0", "https://registry.example/", NOW);
        FindingsProvider.installed().orElseThrow().over(npm).record("npm", "left-pad", "1.3.0", Finding.of(
                "CVE-2026-0007", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "recorded", NOW));

        passNaming("left-pad", NOW);

        assertThat(exposure("1.0").orElseThrow().reached())
                .as("the copy another repository of the tenant holds, found by its coordinate")
                .containsExactly(new ExposureSection.Reached("left-pad", "1.3.0", "npm-proxy", false, 1, "HIGH",
                        List.of(), "npm"));
        assertThat(reliedOnAcross(npm, "npm-proxy", "left-pad").dependents())
                .as("and the copy's page names the release, which relies on it by coordinate")
                .containsExactly(new ReliedOn.Dependent("releases", "Maven", "org.acme:app", "1.0",
                        List.of(new ClosureSection.Hop("left-pad", "1.3.0")), false, true));
        assertThat(reliedOnAcross(tenant.scope("group"), "group", "left-pad").dependents())
                .as("as does the page of any repository's copy: the tenant's rows are the coordinate's")
                .extracting(ReliedOn.Dependent::coordinate).containsExactly("org.acme:app");
    }

    @Test
    void a_new_finding_on_any_copy_reaches_a_release_naming_it_by_coordinate_before_its_full_pass()
            throws IOException {
        ArtifactStore npm = tenant.scope("npm-proxy");
        new StoreRepositoryInventory(npm).cache("npm", "left-pad", "1.3.0", "https://registry.example/", NOW);
        Instant first = NOW.plus(Duration.ofDays(1));
        passNaming("left-pad", first);
        assertThat(exposure("1.0").orElseThrow().reached()).as("nothing found yet").isEmpty();

        // The copy is found vulnerable; neither repository's next pass is a full one, and app, published before the
        // first, is not a recent publish its next pass would visit anyway.
        FindingsProvider.installed().orElseThrow().over(npm).record("npm", "left-pad", "1.3.0", Finding.of(
                "CVE-2026-0008", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "recorded", NOW));
        pass("npm-proxy", Map.of(), null, first.plus(Duration.ofHours(1)));
        passNaming("left-pad", first.plus(Duration.ofHours(1)));

        assertThat(exposure("1.0").orElseThrow().reached())
                .as("followed through the tenant's rows, with no feed asked")
                .extracting(ExposureSection.Reached::coordinate, ExposureSection.Reached::ecosystem,
                        ExposureSection.Reached::repository)
                .containsExactly(tuple("left-pad", "npm", "npm-proxy"));
    }

    @Test
    void a_row_across_the_tenant_goes_once_its_dependent_no_longer_names_the_package() throws IOException {
        passNaming(List.of("left-pad", "right-pad"), NOW);
        ArtifactStore space = tenant.scope(ReliedOn.SPACE);
        assertThat(space.isEmpty(ReliedOn.ROOT)).as("indexed for the tenant").isFalse();

        // app's closure no longer names left-pad: the bill it carries was replaced.
        metadata.mutate("Maven", "org.acme:app", "1.0", ClosureSection.TAG, ClosureSection.record(named(List.of("right-pad"))));
        new ClosureTask(Duration.ofMinutes(5), ClosureSource.installed()).tenant(tenantContext(NOW));

        List<String> left = new ArrayList<>();
        space.scan(ReliedOn.ROOT, "", 100, listed -> left.add(listed.key()));
        assertThat(left).as("the row of what it no longer names goes, the one of what it still names stays")
                .singleElement().satisfies(key -> assertThat(key).contains("right-pad"));
    }

    /** A closure of app 1.0 whose bill names each of {@code names} of npm at 1.3.0, and nothing in its own
     *  ecosystem. */
    private static ClosureSection.Closure named(List<String> names) {
        return new ClosureSection.Closure(ClosureSection.Status.RESOLVED, List.of(), List.of(), false, NOW,
                ClosureSource.Kind.BILL, "bill", names.stream()
                .map(name -> new ClosureSection.Foreign("npm", name, "1.3.0", 1, "", "")).toList());
    }

    /** A pass over the releases whose only source answers {@link #named} {@code name}. */
    private void passNaming(String name, Instant now) throws IOException {
        passNaming(List.of(name), now);
    }

    /** A pass over the releases whose only source answers {@link #named} {@code names}; a release resolved once keeps
     *  its closure, so the source is asked the first time only. */
    private void passNaming(List<String> names, Instant now) throws IOException {
        UnitFailures failures = new UnitFailures("the closure pass", "nothing");
        new ClosureTask(Duration.ofMinutes(5), List.of(source("bill", ClosureSource.Kind.BILL, Set.of("Maven"),
                Optional.of(named(names)), new ArrayList<>()))).repository(context("releases", Map.of(), null, now,
                failures));
        failures.rethrow();
    }

    private ReliedOn.Page reliedOnAcross(ArtifactStore holder, String holderName, String name) throws IOException {
        return ReliedOn.pageAcross(holder, holderName, Optional.of(tenant), named -> Optional.of(tenant.scope(named)),
                _ -> true, "npm", name, "1.3.0", "", 50);
    }

    private TenantContext tenantContext(Instant now) {
        return new TenantContext() {
            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public ArtifactStore store() {
                return tenant;
            }

            @Override
            public ArtifactStore system() {
                return tenant.scope("unused-system");
            }

            @Override
            public UnaryOperator<String> config() {
                return _ -> null;
            }

            @Override
            public long quotaLimit() {
                return 0;
            }

            @Override
            public long recomputeQuota() {
                return 0;
            }

            @Override
            public Instant now() {
                return now;
            }
        };
    }

    @Test
    void a_hold_placed_and_ended_marks_its_version_changed_and_a_repeated_record_does_not() throws IOException {
        String held = "/maven/org/dep/held/1.0/held-1.0.pom";
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8)));
        HeldSubjects.hold(publication, store, held, hash, "Maven", "org.dep:held", "1.0");
        assertThat(changed()).as("a hold placed").containsExactly("org.dep:held 1.0");

        HeldSubjects.record(store, held, "Maven", "org.dep:held", "1.0");
        assertThat(changed()).as("recorded again by a converging sweep").isEmpty();

        HeldSubjects.forget(store, held);
        assertThat(changed()).as("a hold ended").containsExactly("org.dep:held 1.0");
    }

    private List<String> changed() throws IOException {
        List<String> drained = new ArrayList<>();
        ChangedVersions.drain(store, 100, version -> drained.add(version.coordinate() + " " + version.version()));
        return drained;
    }

    private List<ExposureSection.Reached> webExposure() throws IOException {
        return ExposureSection.exposure(MetadataProvider.installed().over(tenant.scope("group"))
                .section("Maven", "org.acme:web", "1.0", ExposureSection.TAG)).orElseThrow().reached();
    }

    /** web 1.0, published to the group, declaring {@code dependency} at 1.0. */
    private void web(String dependency) throws IOException {
        web(dependency, NOW);
    }

    /** web 1.0, published to the group at {@code at}, declaring {@code dependency} at 1.0. */
    private void web(String dependency, Instant at) throws IOException {
        ArtifactStore group = tenant.scope("group");
        String path = "/maven/org/acme/web/1.0/web-1.0.pom";
        Publication publication = new Publication(group);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(
                "<project/>".getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(group).record(path, at);
        MetadataProvider.installed().over(group).mutate("Maven", "org.acme:web", "1.0", DependencySection.TAG,
                DependencySection.record(path, List.of(new DependencySection.Declared(dependency, "1.0")), NOW));
    }

    private ReliedOn.Page reliedOn(String coordinate, String version, Predicate<String> readable)
            throws IOException {
        return ReliedOn.page(store, "releases", name -> Optional.of(tenant.scope(name)), readable, "Maven",
                coordinate, version, "", 50);
    }

    private List<String> rows() throws IOException {
        List<String> keys = new ArrayList<>();
        store.scan(ReliedOn.ROOT, "", 100, listed -> keys.add(listed.key()));
        return keys;
    }

    private Optional<ExposureSection.Exposure> exposure(String version) throws IOException {
        return ExposureSection.exposure(metadata.section("Maven", "org.acme:app", version, ExposureSection.TAG));
    }

    /** A copy of {@code org.dep:<artifact>} the repository cached, its POM declaring nothing. */
    private void cached(String artifact, String version) throws IOException {
        String path = "/maven/org/dep/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom";
        Publication publication = new Publication(store);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(("<project><modelVersion>4.0.0"
                + "</modelVersion><groupId>org.dep</groupId><artifactId>" + artifact + "</artifactId><version>"
                + version + "</version></project>").getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(store).cache("Maven", "org.dep:" + artifact, version,
                "https://repo.example/maven2/", NOW);
    }

    private Optional<ClosureSection.Closure> closure() throws IOException {
        return ClosureSection.closure(metadata.section("Maven", "org.acme:app", "1.0", ClosureSection.TAG));
    }

    private void pass(String setting, Instant now) throws IOException {
        pass("releases", Map.of(), setting, now);
    }

    private void pass(String repository, Map<String, String> config, String setting, Instant now) throws IOException {
        UnitFailures failures = new UnitFailures("the closure pass", "nothing");
        new ClosureTask(Duration.ofMinutes(5), ClosureSource.installed()).repository(context(repository, config, setting,
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
            public Optional<ArtifactStore> tenantStore() {
                return Optional.of(tenant);
            }

            @Override
            public List<String> repositories() {
                return List.of("group", "npm-proxy", "releases");
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
