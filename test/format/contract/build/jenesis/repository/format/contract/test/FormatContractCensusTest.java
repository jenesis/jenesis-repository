package build.jenesis.repository.format.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.format.testkit.TraversalVectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completeness ratchet for the format contract kit: no format may exist without a fixture, and no fixture may name
 * a format that no longer does.
 *
 * <p>Per the plan's first design gate the static and runtime legs are <em>separate</em> assertions, because neither can
 * see the other's blind spot. {@code ServiceLoader} sees only what this module's graph resolved, so it cannot notice a
 * format module the test forgot to {@code requires} - the provider would then be missing from both the discovery set
 * and the comparison, and a ServiceLoader-only census would stay green while a whole format went untested. The static
 * leg parses every source {@code provides ... with ...} clause instead, so it sees a format the runtime graph is blind
 * to; the runtime leg proves the declared classes really resolve and really answer to the name a request routes by.
 * {@link #the_census_trips_when_a_leg_is_broken()} keeps both honest by breaking each on purpose.
 *
 * <p>Two further ratchets point the other way. {@link #every_contract_property_is_exercised_by_some_fixture()} fails if
 * a property every fixture excludes were ever added, so a reason-bearing exclusion can shrink one format's coverage but
 * never the contract's. {@link #every_probe_vector_is_shared_rather_than_per_fixture()} fails if the shared vector list
 * empties out or loses one of its three refusal kinds - the list is the thing that stops each fixture from quietly
 * probing only the shapes it already handles.
 */
class FormatContractCensusTest {

    /** Every fixture the kit registers. Constructing one seeds nothing, so this census runs without touching a store. */
    private static final List<FormatFixture> FIXTURES = List.of(
            new MavenFormatFixture(), new JenesisFormatFixture(), new OciFormatFixture(), new RawFormatFixture());

    /** No format is exempt: all four ship in the free distribution bundle and all four have a fixture. The argument
     *  stays wired so an exemption is a visible, reason-bearing edit rather than a new mechanism. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    private static List<Provider> declared() throws IOException {
        return ContractCensus.declaredProviders(repositoryRoot().resolve("source"), RepositoryFormat.class);
    }

    private static List<Provider> discovered() {
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(format -> Provider.runtime(format.name(), format))
                .toList();
    }

    private static List<String> fixtures() {
        return FIXTURES.stream().map(FormatFixture::providerClass).toList();
    }

    @Test
    void every_declared_and_discovered_format_has_a_fixture() throws IOException {
        ContractCensus.of(RepositoryFormat.class, declared(), discovered(), fixtures(), EXEMPTIONS);
    }

    @Test
    void the_static_provides_scan_sees_every_format_module() throws IOException {
        // The static leg on its own: parsed out of source/**/module-info.java, so it names a format even when this
        // module's graph does not resolve it. Vacuity is the failure mode to guard - an empty or truncated parse would
        // let every other assertion here pass while covering nothing.
        assertThat(declared()).extracting(Provider::implementation)
                .as("the source `provides RepositoryFormat with ...` scan is the census's static leg")
                .containsExactlyInAnyOrder(
                        "build.jenesis.repository.format.maven.MavenFormat",
                        "build.jenesis.repository.format.jenesis.JenesisFormat",
                        "build.jenesis.repository.format.oci.OciFormat",
                        "build.jenesis.repository.format.raw.RawFormat");
    }

    @Test
    void the_runtime_graph_discovers_every_declared_format_under_its_routing_name() throws IOException {
        // The runtime leg on its own, asserted directly rather than through the census helper: every statically
        // declared format really resolves in this module's graph, and answers to the name a request routes by. A
        // format module dropped from this module-info fails here.
        assertThat(discovered()).extracting(Provider::implementation)
                .as("a declared format that ServiceLoader cannot see here means a missing `requires` in "
                        + "test/format/contract/module-info.java - the census graph must root every format module")
                .containsExactlyInAnyOrderElementsOf(declared().stream().map(Provider::implementation).toList());
        assertThat(discovered()).extracting(Provider::name)
                .containsExactlyInAnyOrder("maven", "jenesis", "oci", "raw");
    }

    @Test
    void every_fixture_drives_the_format_it_claims() {
        Map<String, String> byClass = discovered().stream()
                .collect(Collectors.toMap(Provider::implementation, Provider::name));
        for (FormatFixture fixture : FIXTURES) {
            assertThat(byClass.get(fixture.providerClass()))
                    .as("the '%s' fixture must name the provider class that answers to that format name, or the "
                            + "census would count a fixture against a format it never exercises", fixture.format())
                    .isEqualTo(fixture.format());
            // ... and it must reach that format the way the dispatcher does, through the SPI rather than by
            // constructing one: a fixture holding its own instance would test a format the deployment never resolves.
            assertThat(fixture.serving().getClass().getName())
                    .as("the '%s' fixture serves through the discovered format", fixture.format())
                    .isEqualTo(fixture.providerClass());
        }
    }

    @Test
    void every_contract_property_is_exercised_by_some_fixture() {
        Set<FormatContract.Property> exercised = FIXTURES.stream()
                .flatMap(fixture -> FormatContract.checks(fixture).stream())
                .map(FormatContract.Check::property)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FormatContract.Property.class)));

        assertThat(exercised)
                .as("a property every fixture excludes is asserted nowhere; the contract may not shrink by attrition. "
                        + "Either a format must run it, or the property does not belong in the contract.")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(FormatContract.Property.class));

        // ... and an exclusion is never silent: each one names the property and why the format's protocol lacks it.
        for (FormatFixture fixture : FIXTURES) {
            fixture.unsupported().forEach((property, reason) ->
                    assertThat(reason).as("the '%s' fixture's exclusion of %s", fixture.format(), property)
                            .isNotBlank());
        }
    }

    @Test
    void every_probe_vector_is_shared_rather_than_per_fixture() {
        // The vector list is the reason a traversal guard cannot rot format by format: every fixture is probed with
        // the same shapes through its one-line splice, so the format whose author never thought of an encoded
        // traversal is probed with one anyway. Losing a kind here would silently narrow all four legs at once.
        assertThat(TraversalVectors.all()).as("the shared probe vectors").isNotEmpty();
        for (TraversalVectors.Kind kind : TraversalVectors.Kind.values()) {
            assertThat(TraversalVectors.of(kind))
                    .as("every refusal kind must be represented, or the leg stops probing that shape entirely: %s",
                            kind)
                    .isNotEmpty();
        }
        assertThat(TraversalVectors.all()).extracting(TraversalVectors.Vector::id)
                .as("vector ids are unique, so a failure names exactly one shape").doesNotHaveDuplicates();

        // Each fixture's splice really lands on a path its own format claims - a probe the format does not handle
        // would exercise nothing while looking green.
        for (FormatFixture fixture : FIXTURES) {
            for (TraversalVectors.Vector vector : TraversalVectors.all()) {
                assertThat(fixture.serving().handles(fixture.probe(vector.relative())))
                        .as("the '%s' fixture's '%s' probe lands on a path that format claims", fixture.format(),
                                vector.id())
                        .isTrue();
            }
        }
    }

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();

        assertThatThrownBy(() -> ContractCensus.of(RepositoryFormat.class, declared, discovered,
                fixtures().subList(0, fixtures().size() - 1), EXEMPTIONS))
                .as("dropping one fixture must fail the census - the ratchet's whole purpose")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(RepositoryFormat.class, declared,
                discovered.subList(0, discovered.size() - 1), fixtures(), EXEMPTIONS))
                .as("a declared format the runtime graph cannot see must fail even though ServiceLoader is happy - "
                        + "the blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(RepositoryFormat.class,
                declared.subList(0, declared.size() - 1), discovered, fixtures(), EXEMPTIONS))
                .as("a runtime format no source clause declares must fail too - an undeclared format is as much a "
                        + "packaging error as a missing one")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("format").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
