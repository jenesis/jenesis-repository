package build.jenesis.repository.importer.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completeness ratchet for the import-connector contract kit: no connector may exist without a behavioural fixture,
 * and no fixture may name a connector that no longer does.
 *
 * <p>Per the plan's first design gate the static and runtime legs are <em>separate</em> assertions, because neither can
 * see the other's blind spot. {@code ServiceLoader} sees only what this module's graph resolved, so it cannot notice a
 * connector module the test forgot to {@code requires} - the provider would then be missing from both the discovery set
 * and the comparison, and a discovery-only census would stay green while a whole connector went untested. The static
 * leg parses every source {@code provides ... with ...} clause instead, so it sees a connector the runtime graph is
 * blind to; the runtime leg proves the declared classes really resolve and really answer to the source name an operator
 * submits. {@link #the_census_trips_when_a_leg_is_broken()} keeps both honest by breaking each on purpose.
 */
class ImportSourceCensusTest {

    /** Every fixture the kit registers. Constructing one scripts nothing, so this census runs without a walk. */
    private static final List<ImportFixture> FIXTURES = List.of(
            new NexusImportFixture(), new ArtifactoryImportFixture(), new MavenImportFixture(),
            new IndexImportFixture(), new JenesisImportFixture());

    /** No connector is exempt: all five ship in the free distribution and all five have a behavioural fixture. The
     *  argument stays wired so an exemption would be a visible, reason-bearing edit rather than a new mechanism. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    private static List<Provider> declared() throws IOException {
        return ContractCensus.declaredProviders(repositoryRoot().resolve("source"), ImportSourceProvider.class);
    }

    private static List<Provider> discovered() {
        return ServiceLoader.load(ImportSourceProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(provider -> Provider.runtime(provider.name(), provider))
                .toList();
    }

    private static List<String> fixtures() {
        return FIXTURES.stream().map(ImportFixture::providerClass).toList();
    }

    @Test
    void every_declared_and_discovered_connector_has_a_fixture() throws IOException {
        ContractCensus.of(ImportSourceProvider.class, declared(), discovered(), fixtures(), EXEMPTIONS);
    }

    @Test
    void the_static_provides_scan_sees_every_connector_module() throws IOException {
        // The static leg on its own: parsed out of source/**/module-info.java, so it names a connector even when this
        // module's graph does not resolve it. Vacuity is the failure mode to guard - an empty or truncated parse would
        // let every other assertion here pass while covering nothing.
        assertThat(declared()).extracting(Provider::implementation)
                .as("the source `provides ImportSourceProvider with ...` scan is the census's static leg")
                .containsExactlyInAnyOrder(
                        "build.jenesis.repository.importer.nexus.NexusSourceProvider",
                        "build.jenesis.repository.importer.artifactory.ArtifactorySourceProvider",
                        "build.jenesis.repository.importer.maven.MavenSourceProvider",
                        "build.jenesis.repository.importer.index.IndexSourceProvider",
                        "build.jenesis.repository.importer.jenesis.JenesisSourceProvider");
    }

    @Test
    void the_runtime_graph_discovers_every_declared_connector_under_its_submission_name() throws IOException {
        // The runtime leg on its own, asserted directly rather than through the census helper: every statically
        // declared connector really resolves in this module's graph, and answers to the name an operator submits. A
        // connector module dropped from this module-info fails here.
        assertThat(discovered()).extracting(Provider::implementation)
                .as("a declared connector that ServiceLoader cannot see here means a missing `requires` in "
                        + "test/importer/contract/module-info.java - the census graph must root every connector module")
                .containsExactlyInAnyOrderElementsOf(declared().stream().map(Provider::implementation).toList());
        assertThat(discovered()).extracting(Provider::name)
                .containsExactlyInAnyOrder("nexus", "artifactory", "maven", "index", "jenesis");
    }

    @Test
    void every_fixture_drives_the_connector_it_claims() {
        Map<String, String> byClass = discovered().stream()
                .collect(Collectors.toMap(Provider::implementation, Provider::name));
        for (ImportFixture fixture : FIXTURES) {
            assertThat(byClass.get(fixture.providerClass()))
                    .as("the '%s' fixture must name the provider class that answers to that source name, or the "
                            + "census would count a fixture against a connector it never exercises", fixture.source())
                    .isEqualTo(fixture.source());
            // ... and it must reach that provider the way the server does, through ServiceLoader rather than by
            // constructing one: a fixture holding its own instance would test a connector no deployment resolves.
            assertThat(fixture.provider().getClass().getName())
                    .as("the '%s' fixture drives the discovered provider", fixture.source())
                    .isEqualTo(fixture.providerClass());
        }
    }

    @Test
    void every_contract_property_is_exercised_by_some_fixture() {
        Set<ImportContract.Property> exercised = FIXTURES.stream()
                .flatMap(fixture -> ImportContract.checks(fixture).stream())
                .map(ImportContract.Check::property)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ImportContract.Property.class)));

        assertThat(exercised)
                .as("a property every fixture excludes is asserted nowhere; the contract may not shrink by attrition. "
                        + "Either a connector must run it, or the property does not belong in the contract.")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ImportContract.Property.class));

        // ... and an exclusion is never silent: each one names the property and why the connector's protocol lacks it.
        for (ImportFixture fixture : FIXTURES) {
            fixture.unsupported().forEach((property, reason) ->
                    assertThat(reason).as("the '%s' fixture's exclusion of %s", fixture.source(), property)
                            .isNotBlank());
        }
    }

    @Test
    void the_hostile_listing_leg_is_probed_by_some_fixture() {
        // The path leg's corpus half runs everywhere, but its hostile half only where a connector's listing format can
        // express a laced entry. If every fixture stopped supplying one, the leg would quietly become "the well-formed
        // corpus is well-formed" - green, and blind to the shape it exists to catch.
        List<String> probing = FIXTURES.stream()
                .filter(fixture -> fixture.hostile().isPresent())
                .map(ImportFixture::source)
                .toList();
        assertThat(probing)
                .as("at least one connector must script a traversal-laced listing, or the skip-a-laced-entry half of "
                        + "%s is asserted nowhere", ImportContract.Property.REPORTS_ONLY_IMPORTABLE_PATHS)
                .isNotEmpty();
    }

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();

        assertThatThrownBy(() -> ContractCensus.of(ImportSourceProvider.class, declared, discovered,
                fixtures().subList(0, fixtures().size() - 1), EXEMPTIONS))
                .as("dropping one fixture must fail the census - the ratchet's whole purpose")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(ImportSourceProvider.class, declared,
                discovered.subList(0, discovered.size() - 1), fixtures(), EXEMPTIONS))
                .as("a declared connector the runtime graph cannot see must fail even though ServiceLoader is happy - "
                        + "the blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(ImportSourceProvider.class,
                declared.subList(0, declared.size() - 1), discovered, fixtures(), EXEMPTIONS))
                .as("a runtime connector no source clause declares must fail too - an undeclared connector is as much "
                        + "a packaging error as a missing one")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            // Standalone the tree is source/; inside an enclosing project it is free/source/.
            if (Files.isDirectory(candidate.resolve("free").resolve("source").resolve("importer").resolve("spi"))) {
                return candidate.resolve("free");
            }
            if (Files.isDirectory(candidate.resolve("source").resolve("importer").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
