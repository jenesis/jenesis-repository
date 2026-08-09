package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.testkit.WalkConsumerContract;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completeness ratchet for the walk-consumer contract kit: no {@link WalkConsumer} may exist without a fixture,
 * and no fixture may name a consumer that no longer does.
 *
 * <p>Per the plan's first design gate the static and runtime legs are <em>separate</em> assertions.
 * {@code ServiceLoader} sees only what this module's graph resolved, so it cannot notice a consumer module the test
 * forgot to {@code requires}; the static leg parses every source {@code provides ... with ...} clause instead, so it
 * sees a consumer the runtime graph is blind to. {@link #the_census_trips_when_a_leg_is_broken()} keeps both honest by
 * breaking each on purpose.
 *
 * <p><b>The finding this census records.</b> The static leg over {@code source/} is <em>empty</em>: neither repository
 * ships a production {@code WalkConsumer} at these tips. The SPI, the shared {@code RebuildPass} and the downstream
 * {@code RebuildTask} that drives it are all in place, but nothing is plugged into them - so the walk half of the
 * two-route derived-metadata contract has no adopters, and every derived surface that needs a back-fill hand-rolls its
 * own sweep instead ({@code OciInventoryBackfillTask}, {@code FindingsMigrationTask}, {@code MetadataMigrationTask};
 * see {@link #the_shipped_consumer_inventory_is_still_empty()}). The kit's fixtures are therefore the three delivery
 * archetypes the SPI documents rather than shipped consumers - which is what makes the crash checks assert the SPI's
 * promise instead of one implementation's habits - and this test is the ratchet that turns the first shipped consumer
 * into a demand for a fixture rather than a silent gap.
 */
class WalkConsumerCensusTest {

    /** Every fixture the kit registers - one per delivery class the walk's commit protocol supports. */
    private static final List<WalkConsumerFixture> FIXTURES = List.of(
            new StreamingIndexFixture(), new StrideBufferedFixture(), new PassSnapshotFixture());

    /** No consumer in this graph is exempt: all three archetypes are declared here and all three have a fixture. The
     *  argument stays wired so an exemption is a visible, reason-bearing edit rather than a new mechanism. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    /**
     * The {@code WalkConsumer} providers that exist but that this module's graph cannot reach, each with the reason
     * and the ticket that will cover it. They are deliberately <em>not</em> fed to {@link ContractCensus}: the helper
     * requires every declared provider to be runtime-discoverable here, which is exactly the property that makes its
     * census meaningful, and a provider in another test module's graph (or in another repository altogether) can never
     * satisfy it. Keeping them here as data, with the FREE entry checked against the source tree below, is the honest
     * alternative to widening the helper until it stops asserting anything.
     */
    private static final Map<String, String> OUT_OF_GRAPH = Map.of(
            "build.jenesis.repository.walk.test.DiscoverableWalkConsumer",
            "FREE test/walk: the discovery probe for WalkConsumer.discovered(), asserted by WalkConsumerDiscoveryTest. "
                    + "It holds no durable state, so it has no projection to converge and no contract leg to run; a "
                    + "test module cannot require another test module, so it is unreachable from this graph either.",
            "build.jenesis.repository.reclamation.test.RecordingWalkConsumer",
            "ENT test/reclamation: the recording consumer RebuildTaskTest drives. Downstream has no production "
                    + "WalkConsumer to fixture yet, and the free module graph cannot see a downstream test module; an "
                    + "downstream slice of T-204 (none is in the tracker today - the orchestrator needs to add one) "
                    + "owns the downstream-side fixtures once downstream ships a consumer.");

    private static List<Provider> declared() throws IOException {
        // Two roots, and the pair is the point. `source/` is the shipped inventory - the thing that must never grow a
        // consumer without a fixture. This module is where the kit's own archetypes are declared, so the census has a
        // non-empty population to verify while the shipped one is empty. The day a source module provides a consumer,
        // the static leg grows and the runtime leg fails until this module requires it: gate 1, working as intended.
        List<Provider> providers = new ArrayList<>(shipped());
        providers.addAll(ContractCensus.declaredProviders(
                repositoryRoot().resolve("test").resolve("walkconsumer"), WalkConsumer.class));
        return List.copyOf(providers);
    }

    private static List<Provider> shipped() throws IOException {
        return ContractCensus.declaredProviders(repositoryRoot().resolve("source"), WalkConsumer.class);
    }

    private static List<Provider> discovered() {
        return ServiceLoader.load(WalkConsumer.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(consumer -> Provider.runtime(consumer.name(), consumer))
                .toList();
    }

    private static List<String> fixtures() {
        return FIXTURES.stream().map(WalkConsumerFixture::providerClass).toList();
    }

    @Test
    void every_declared_and_discovered_consumer_has_a_fixture() throws IOException {
        ContractCensus.of(WalkConsumer.class, declared(), discovered(), fixtures(), EXEMPTIONS);
    }

    @Test
    void the_shipped_consumer_inventory_is_still_empty() throws IOException {
        // The static leg over source/, alone. Today it is empty, and that emptiness is the finding: the walk half of
        // the two-route derived-metadata contract has no adopters in this repository. When it stops being empty this
        // fails, and the fix is to add the new consumer's fixture to FIXTURES (and its module to this module-info),
        // not to relax the assertion - a shipped consumer with no crash-resume fixture is exactly the gap this kit
        // exists to close.
        assertThat(shipped()).extracting(Provider::implementation)
                .as("a source module now provides a WalkConsumer. Give it a WalkConsumerFixture declaring its "
                        + "projection and its delivery class, require its module here, and add it to FIXTURES.")
                .isEmpty();
    }

    @Test
    void the_runtime_graph_discovers_every_declared_consumer_under_its_toggle_name() throws IOException {
        // The runtime leg on its own, asserted directly rather than through the census helper: every statically
        // declared consumer really resolves in this module's graph, and answers to the name its Features toggle uses.
        assertThat(discovered()).extracting(Provider::implementation)
                .as("a declared consumer that ServiceLoader cannot see here means a missing `requires` in "
                        + "test/walkconsumer/module-info.java - the census graph must root every consumer module")
                .containsExactlyInAnyOrderElementsOf(declared().stream().map(Provider::implementation).toList());
        assertThat(discovered()).extracting(Provider::name)
                .containsExactlyInAnyOrder("walkkit-streaming", "walkkit-buffered", "walkkit-snapshot");

        // ... and the SPI's own discovery static agrees, toggles included - that is what the scheduled pass calls, so
        // a consumer discoverable only through a raw ServiceLoader would never actually be driven.
        assertThat(WalkConsumer.discovered()).extracting(consumer -> consumer.getClass().getName())
                .containsAll(fixtures());
    }

    @Test
    void every_fixture_drives_the_consumer_it_claims() {
        Map<String, String> byClass = discovered().stream()
                .collect(Collectors.toMap(Provider::implementation, Provider::name));
        for (WalkConsumerFixture fixture : FIXTURES) {
            assertThat(byClass.get(fixture.providerClass()))
                    .as("the '%s' fixture must name the provider class that answers to that consumer name, or the "
                            + "census would count a fixture against a consumer it never exercises", fixture.consumer())
                    .isEqualTo(fixture.consumer());
            // ... and it must reach that consumer the way the scheduled pass does, through discovery rather than by
            // construction: a fixture holding its own instance would test a consumer the deployment never resolves.
            assertThat(fixture.create().getClass().getName())
                    .as("the '%s' fixture drives the discovered consumer", fixture.consumer())
                    .isEqualTo(fixture.providerClass());
            assertThat(fixture.create())
                    .as("every simulated process gets its own instance, or an 'in-memory state died with the "
                            + "process' crash check would silently keep its accumulation")
                    .isNotSameAs(fixture.create());
        }
    }

    @Test
    void every_delivery_class_is_represented_by_a_fixture() {
        // The kit's post-crash assertion is chosen by delivery class, so a class with no fixture means one of the two
        // claims - "converged" and "converged or visibly degraded, never partial" - is asserted nowhere at all.
        assertThat(FIXTURES).extracting(WalkConsumerFixture::delivery)
                .as("every delivery class the walk's commit protocol supports needs an archetype, or the kit stops "
                        + "distinguishing between them and silently holds one class to another's guarantee")
                .containsExactlyInAnyOrderElementsOf(
                        EnumSet.allOf(WalkConsumerFixture.Delivery.class));
    }

    @Test
    void every_contract_property_is_exercised_by_some_fixture() {
        Set<WalkConsumerContract.Property> exercised = FIXTURES.stream()
                .flatMap(fixture -> WalkConsumerContract.checks(fixture).stream())
                .map(WalkConsumerContract.Check::property)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(WalkConsumerContract.Property.class)));

        assertThat(exercised)
                .as("a property every fixture excludes is asserted nowhere; the contract may not shrink by attrition. "
                        + "Either a consumer must run it, or the property does not belong in the contract.")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(WalkConsumerContract.Property.class));

        // ... and every crash point the kit knows about really is one of those properties, so a point cannot be added
        // to the enum and then never injected.
        assertThat(EnumSet.allOf(WalkConsumerContract.CrashPoint.class))
                .extracting(WalkConsumerContract.CrashPoint::property)
                .as("every crash point proves a contract property")
                .allSatisfy(property -> assertThat(exercised).contains(property));

        for (WalkConsumerFixture fixture : FIXTURES) {
            fixture.unsupported().forEach((property, reason) ->
                    assertThat(reason).as("the '%s' fixture's exclusion of %s", fixture.consumer(), property)
                            .isNotBlank());
        }
    }

    @Test
    void the_out_of_graph_consumers_stay_live_and_named() throws IOException {
        assertThat(OUT_OF_GRAPH).allSatisfy((provider, reason) ->
                assertThat(reason).as("%s carries a reason naming where it is covered", provider).isNotBlank());

        // The FREE entry is checkable from here, so it is checked: if test/walk stops declaring its discovery probe,
        // this entry is stale and must go, exactly as a ContractCensus exemption would have to.
        List<String> inTestWalk = ContractCensus
                .declaredProviders(repositoryRoot().resolve("test").resolve("walk"), WalkConsumer.class)
                .stream().map(Provider::implementation).toList();
        assertThat(inTestWalk)
                .as("the free-side out-of-graph entry names a consumer test/walk really declares")
                .containsExactly("build.jenesis.repository.walk.test.DiscoverableWalkConsumer");
        assertThat(OUT_OF_GRAPH).containsKey("build.jenesis.repository.walk.test.DiscoverableWalkConsumer");
    }

    @Test
    void the_reference_walk_resolves_through_its_provider_spi() {
        // The suites construct the walk directly, because a crash check needs a movable clock and a tiny checkpoint
        // stride. That is the only reason, and this leg is what keeps it from hiding a broken resolution path: the
        // module graph really does resolve a walk through the SPI, and it is the store reference implementation.
        Optional<ArtifactWalk> resolved = WalkProvider.resolve(_ -> null);
        assertThat(resolved).as("a walk-riding surface resolves the reference walk on this graph").isPresent();
        assertThat(resolved.orElseThrow().getClass().getName())
                .isEqualTo("build.jenesis.repository.walk.store.StoreArtifactWalk");
        assertThat(WalkProvider.installed()).as("and the capability signal agrees").isTrue();
    }

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();

        assertThatThrownBy(() -> ContractCensus.of(WalkConsumer.class, declared, discovered,
                fixtures().subList(0, fixtures().size() - 1), EXEMPTIONS))
                .as("dropping one fixture must fail the census - the ratchet's whole purpose")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(WalkConsumer.class, declared,
                discovered.subList(0, discovered.size() - 1), fixtures(), EXEMPTIONS))
                .as("a declared consumer the runtime graph cannot see must fail even though ServiceLoader is happy - "
                        + "the blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(WalkConsumer.class,
                declared.subList(0, declared.size() - 1), discovered, fixtures(), EXEMPTIONS))
                .as("a runtime consumer no source or kit clause declares must fail too - an undeclared consumer is as "
                        + "much a packaging error as a missing one")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("walk").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
