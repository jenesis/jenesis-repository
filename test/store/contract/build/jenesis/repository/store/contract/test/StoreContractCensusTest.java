package build.jenesis.repository.store.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completeness ratchet for the store contract kit: no backend may exist without a fixture, and no fixture may name
 * a backend that no longer does.
 *
 * <p>Per the plan's first design gate the static and runtime legs are <em>separate</em> assertions, because neither
 * can see the other's blind spot. {@code ServiceLoader} sees only what this module's graph resolved, so it cannot
 * notice a backend module the test forgot to {@code requires} - the provider would then be missing from both the
 * discovery set and the comparison, and a ServiceLoader-only census would stay green while a whole backend went
 * untested. The static leg parses every source {@code provides ... with ...} clause instead, so it sees a backend the
 * runtime graph is blind to; the runtime leg proves the declared classes really resolve and really answer to the name
 * an operator configures. {@link #the_census_trips_when_a_leg_is_broken()} keeps both legs honest by breaking each on
 * purpose.
 *
 * <p>A third ratchet points the other way: {@link #every_contract_property_is_exercised_by_some_fixture()} fails if a
 * property every fixture excludes were ever added, so a fixture's reason-bearing exclusion can shrink one backend's
 * coverage but never the contract's. A fourth,
 * {@link #an_unavailable_fixture_skips_by_default_and_fails_the_strict_lane()}, pins the other way a backend can go
 * uncovered: a containerised fixture that cannot start must skip on a developer machine and <em>fail</em> under the
 * strict lane's {@code -Djenesis.test.required}, so "green" there can never mean "never ran".
 */
class StoreContractCensusTest {

    /** Every fixture the kit registers. Constructing one starts nothing, so this census runs without a Docker daemon
     *  and still proves the containerised backends are covered. */
    private static final List<StoreFixture> FIXTURES = List.of(
            new FilesystemStoreFixture(), new S3StoreFixture(), new GcsStoreFixture(), new AzureStoreFixture());

    /** No backend is exempt: all four ship in the free distribution bundle and all four have a fixture. The argument
     *  stays wired so an exemption is a visible, reason-bearing edit rather than a new mechanism. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    private static List<Provider> declared() throws IOException {
        return ContractCensus.declaredProviders(repositoryRoot().resolve("source"), ArtifactStoreProvider.class);
    }

    private static List<Provider> discovered() {
        return ServiceLoader.load(ArtifactStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(provider -> Provider.runtime(provider.name(), provider))
                .toList();
    }

    private static List<String> fixtures() {
        return FIXTURES.stream().map(StoreFixture::providerClass).toList();
    }

    @Test
    void every_declared_and_discovered_store_backend_has_a_fixture() throws IOException {
        ContractCensus.of(ArtifactStoreProvider.class, declared(), discovered(), fixtures(), EXEMPTIONS);
    }

    @Test
    void the_static_provides_scan_sees_every_backend_module() throws IOException {
        // The static leg on its own: parsed out of source/**/module-info.java, so it names a backend even when this
        // module's graph does not resolve it. Vacuity is the failure mode to guard - an empty or truncated parse would
        // let every other assertion here pass while covering nothing.
        assertThat(declared()).extracting(Provider::implementation)
                .as("the source `provides ArtifactStoreProvider with ...` scan is the census's static leg")
                .containsExactlyInAnyOrder(
                        "build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider",
                        "build.jenesis.repository.store.s3.S3ArtifactStoreProvider",
                        "build.jenesis.repository.store.gcs.GcsArtifactStoreProvider",
                        "build.jenesis.repository.store.azure.AzureArtifactStoreProvider");
    }

    @Test
    void the_runtime_graph_discovers_every_declared_backend_under_its_configured_name() throws IOException {
        // The runtime leg on its own, asserted directly rather than through the census helper: every statically
        // declared backend really resolves in this module's graph, and answers to the name an operator writes as
        // jenesis.repository.store. A backend module dropped from this module-info fails here.
        assertThat(discovered()).extracting(Provider::implementation)
                .as("a declared backend that ServiceLoader cannot see here means a missing `requires` in "
                        + "test/store/contract/module-info.java - the census graph must root every backend module")
                .containsExactlyInAnyOrderElementsOf(declared().stream().map(Provider::implementation).toList());
        assertThat(discovered()).extracting(Provider::name)
                .containsExactlyInAnyOrder("filesystem", "s3", "gcs", "azure-blob");
    }

    @Test
    void every_fixture_drives_the_backend_it_claims() {
        Map<String, String> byClass = discovered().stream()
                .collect(Collectors.toMap(Provider::implementation, Provider::name));
        for (StoreFixture fixture : FIXTURES) {
            assertThat(byClass.get(fixture.providerClass()))
                    .as("the '%s' fixture must name the provider class that answers to that backend name, or the "
                            + "census would count a fixture against a backend it never exercises", fixture.backend())
                    .isEqualTo(fixture.backend());
        }
    }

    @Test
    void every_contract_property_is_exercised_by_some_fixture() {
        Set<StoreContract.Property> exercised = FIXTURES.stream()
                .flatMap(fixture -> StoreContract.checks(fixture).stream())
                .map(StoreContract.Check::property)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StoreContract.Property.class)));

        assertThat(exercised)
                .as("a property every fixture excludes is asserted nowhere; the contract may not shrink by attrition. "
                        + "Either a backend must run it, or the property does not belong in the contract.")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(StoreContract.Property.class));

        // ... and an exclusion is never silent: each one names the property and why its environment cannot express it.
        for (StoreFixture fixture : FIXTURES) {
            fixture.unsupported().forEach((property, reason) ->
                    assertThat(reason).as("the '%s' fixture's exclusion of %s", fixture.backend(), property)
                            .isNotBlank());
        }
    }

    @Test
    void an_unavailable_fixture_skips_by_default_and_fails_the_strict_lane() {
        StoreFixture required = probe("Docker is required for the probe fixture", true);
        StoreFixture entitlement = probe("no live cloud credentials", false);
        String restore = System.getProperty(StoreFixture.REQUIRED_PROPERTY);
        try {
            System.clearProperty(StoreFixture.REQUIRED_PROPERTY);
            assertThat(StoreFixture.skipReason(required))
                    .as("on a developer machine an unstartable backend is a skip, so a checkout without Docker builds")
                    .hasValue("Docker is required for the probe fixture");

            // The strict lane sets the bare flag (no value); the ci profile's process override injects exactly this.
            System.setProperty(StoreFixture.REQUIRED_PROPERTY, "");
            assertThatThrownBy(() -> StoreFixture.skipReason(required))
                    .as("where the environment is declared complete, a backend that cannot start is a FAILURE - a "
                            + "self-skip there is a broken lane reported as green")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("declares this environment complete");

            assertThat(StoreFixture.skipReason(entitlement))
                    .as("an entitlement CI cannot install (a live cloud account) still skips, even on the strict lane")
                    .hasValue("no live cloud credentials");

            for (StoreFixture fixture : FIXTURES) {
                assertThat(fixture.required())
                        .as("the '%s' fixture needs only a container image, which the strict lane's environment "
                                + "provides, so it must be required there", fixture.backend())
                        .isTrue();
            }
        } finally {
            if (restore == null) {
                System.clearProperty(StoreFixture.REQUIRED_PROPERTY);
            } else {
                System.setProperty(StoreFixture.REQUIRED_PROPERTY, restore);
            }
        }
    }

    /** A fixture that reports itself unstartable, for the skip-versus-fail control. It never starts anything. */
    private static StoreFixture probe(String reason, boolean required) {
        return new StoreFixture() {
            @Override
            public String backend() {
                return "probe";
            }

            @Override
            public String providerClass() {
                return "build.jenesis.repository.store.contract.test.Probe";
            }

            @Override
            public Optional<String> unavailable() {
                return Optional.of(reason);
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public void start() {
                throw new AssertionError("an unavailable fixture is never started");
            }

            @Override
            public ArtifactStore store() {
                throw new AssertionError("an unavailable fixture hands out no store");
            }
        };
    }

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();

        assertThatThrownBy(() -> ContractCensus.of(ArtifactStoreProvider.class, declared, discovered,
                fixtures().subList(0, fixtures().size() - 1), EXEMPTIONS))
                .as("dropping one fixture must fail the census - the ratchet's whole purpose")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(ArtifactStoreProvider.class, declared,
                discovered.subList(0, discovered.size() - 1), fixtures(), EXEMPTIONS))
                .as("a declared backend the runtime graph cannot see must fail even though ServiceLoader is happy - "
                        + "the blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(ArtifactStoreProvider.class,
                declared.subList(0, declared.size() - 1), discovered, fixtures(), EXEMPTIONS))
                .as("a runtime provider no source clause declares must fail too - an undeclared backend is as much a "
                        + "packaging error as a missing one")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            // Standalone the tree is source/; inside an enclosing project it is free/source/.
            if (Files.isDirectory(candidate.resolve("free").resolve("source").resolve("store").resolve("spi"))) {
                return candidate.resolve("free");
            }
            if (Files.isDirectory(candidate.resolve("source").resolve("store").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
