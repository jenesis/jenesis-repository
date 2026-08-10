package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Delivery;
import build.jenesis.repository.store.testkit.PublicationHookFixture.ObserverLeg;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Role;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completeness ratchet for the publication-hook kit, and the place the <b>role split</b> is proven three
 * independent ways: from the provider's source, from the runtime graph, and from {@link Publication}'s own behaviour.
 *
 * <p>Per the plan's first design gate the static and runtime legs are separate assertions. {@code ServiceLoader} sees
 * only what this module's graph resolved, so it cannot notice a hook module the test forgot to {@code requires}; the
 * static leg parses every source {@code provides ... with ...} clause instead, so it sees a hook the runtime graph is
 * blind to. {@link #the_census_trips_when_a_leg_is_broken()} keeps both honest by breaking each on purpose.
 *
 * <p><b>Why the role needs its own census leg at all.</b> One {@code uses PublicationObserver} clause discovers a
 * contained after-commit observer and a fail-closed pre-commit screen alike - providers with <em>opposite</em> failure
 * semantics - and only {@code instanceof PublishInterceptor} tells them apart. A census that counted the family as one
 * homogeneous population would happily drive a screen through the observer legs and report a green for a hook that
 * fails open. So the role is derived from the source declaration, derived again from the runtime instance, compared,
 * and then confirmed against what {@code Publication} actually did with the discovered list.
 *
 * <p><b>The finding this census records.</b> The static leg over {@code source/} is <em>empty</em>: the core
 * ships no {@code PublicationObserver} and no {@code PublishInterceptor}, exactly as the SPI's own contract says, so
 * the shipped chain is empty and every upload is accepted and served. The kit's fixtures are therefore the role and
 * delivery archetypes the SPI documents, which is what makes the checks assert the stated contract rather than one
 * implementation's habits - and this test is the ratchet that turns the first shipped hook into a demand for a
 * fixture rather than a silent gap.
 */
class PublicationHookCensusTest {

    /** Every fixture the kit registers - one per role, and one per delivery class the seam supports. */
    private static final List<PublicationHookFixture> FIXTURES = List.of(
            new IndexObserverFixture(), new OutboxObserverFixture(), new RecordingScreenFixture(),
            new WithholdingScreenFixture(), new AuditingScreenFixture(), new OverrideHookFixture());

    /** No hook in this graph is exempt. The argument stays wired so an exemption is a visible, reason-bearing edit
     *  rather than a new mechanism. */
    private static final List<Exemption> EXEMPTIONS = List.of();

    /**
     * The publication hooks that exist but that this module's graph cannot reach, each with the reason and the ticket
     * that covers it. They are deliberately <em>not</em> fed to {@link ContractCensus}: the helper requires every
     * declared provider to be runtime-discoverable here, which is exactly the property that makes its census
     * meaningful, and a provider in another repository can never satisfy it. Keeping them here as data, each keyed to
     * the role its downstream source declares, is the honest alternative to widening the helper until it stops
     * asserting anything.
     *
     * <p>All fourteen are <b>T-205b's</b> to fixture. The split matters: the three interceptors and the four
     * hold-release hooks are pre-commit and fail-closed, and running any of them through the after-commit legs would
     * assert the opposite of their contract.
     */
    private static final Map<String, Role> OUT_OF_GRAPH = Map.ofEntries(
            // ENT after-commit observers - contained, best-effort, repaired by the walk (T-107's migration candidates)
            Map.entry("build.jenesis.repository.forwarding.ForwardingObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.webhook.WebhookPublicationObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.index.IndexRetractionObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.search.lucene.SearchPublicationObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.dependents.DependentsPublicationObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.inventory.SubtreeSizePublicationObserver", Role.AFTER_COMMIT_OBSERVER),
            Map.entry("build.jenesis.repository.compliance.web.ProvenanceAttestationReaper",
                    Role.AFTER_COMMIT_OBSERVER),
            // ENT pre-commit screens - the verdict legs propagate, riding the same `uses PublicationObserver` clause
            Map.entry("build.jenesis.repository.gate.ComplianceScreen", Role.PUBLISH_INTERCEPTOR),
            Map.entry("build.jenesis.repository.staging.store.StagingWithholdInterceptor", Role.PUBLISH_INTERCEPTOR),
            Map.entry("build.jenesis.repository.gate.OciHoldRecorder", Role.PUBLISH_INTERCEPTOR),
            // ENT hold-release hooks - pre-commit, fail-closed, and NOT PublicationObservers despite the name
            Map.entry("build.jenesis.repository.gate.KevHoldReleaseObserver", Role.PRE_COMMIT_RELEASE_HOOK),
            Map.entry("build.jenesis.repository.gate.LicenseHoldReleaseObserver", Role.PRE_COMMIT_RELEASE_HOOK),
            Map.entry("build.jenesis.repository.security.reachability.ReachabilityHoldReleaseObserver",
                    Role.PRE_COMMIT_RELEASE_HOOK),
            Map.entry("build.jenesis.repository.findings.store.DiscardedHoldFindingsObserver",
                    Role.PRE_COMMIT_RELEASE_HOOK));

    private static final Pattern COMMENTS = Pattern.compile("//[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    @TempDir
    Path root;

    // --- the static and runtime legs, separately ---------------------------------------------------------------

    private static List<Provider> declared() throws IOException {
        // Two roots, and the pair is the point. `source/` is the shipped inventory - the thing that must never grow a
        // hook without a fixture. This module is where the kit's own archetypes are declared, so the census has a
        // non-empty population to verify while the shipped one is empty. The day a source module provides a hook, the
        // static leg grows and the runtime leg fails until this module requires it: gate 1, working as intended.
        List<Provider> providers = new ArrayList<>(shipped());
        providers.addAll(ContractCensus.declaredProviders(
                repositoryRoot().resolve("test").resolve("publication"), PublicationObserver.class));
        return List.copyOf(providers);
    }

    private static List<Provider> shipped() throws IOException {
        return ContractCensus.declaredProviders(repositoryRoot().resolve("source"), PublicationObserver.class);
    }

    private static List<Provider> discovered() {
        return ServiceLoader.load(PublicationObserver.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(hook -> Provider.runtime(hook.getClass().getName(), hook))
                .toList();
    }

    /** Only the fixtures whose hook rides the one discovered clause - the hold-release role does not, because it is
     *  not a {@code PublicationObserver} at all. */
    private static List<String> discoverableFixtures() {
        return FIXTURES.stream()
                .filter(fixture -> fixture.role() != Role.PRE_COMMIT_RELEASE_HOOK)
                .map(PublicationHookFixture::providerClass)
                .toList();
    }

    @Test
    void every_declared_and_discovered_hook_has_a_fixture() throws IOException {
        ContractCensus.of(PublicationObserver.class, declared(), discovered(), discoverableFixtures(), EXEMPTIONS);
    }

    @Test
    void the_shipped_hook_inventory_is_still_empty() throws IOException {
        // The static leg over source/, alone. Today it is empty, and that emptiness is the SPI's own claim: "the free
        // product ships no interceptor at all, so the shipped chain is empty". When it stops being empty this fails,
        // and the fix is to add the new hook's fixture to FIXTURES (and its module to this module-info), not to relax
        // the assertion - a shipped hook with no fail-closed or crash-window fixture is exactly the gap this kit
        // exists to close.
        assertThat(shipped()).extracting(Provider::implementation)
                .as("a source module now provides a PublicationObserver. Give it a PublicationHookFixture - an "
                        + "Observer one with its delivery class and repair leg, or an Interceptor one with its "
                        + "verdicts and the keys its verdict reads - require its module here, and add it to FIXTURES.")
                .isEmpty();
    }

    // --- the role split, derived three ways --------------------------------------------------------------------

    @Test
    void the_role_of_every_declared_hook_agrees_between_its_source_and_the_runtime_graph() throws IOException {
        Map<String, Role> fromSource = new TreeMap<>();
        Map<String, Role> fromRuntime = new TreeMap<>();
        for (Provider provider : declared()) {
            fromSource.put(provider.implementation(), roleInSource(provider.implementation()));
        }
        for (ServiceLoader.Provider<PublicationObserver> provider
                : ServiceLoader.load(PublicationObserver.class).stream().toList()) {
            fromRuntime.put(provider.type().getName(), Role.of(provider.get()));
        }

        assertThat(fromRuntime)
                .as("the role a hook is keyed to must be the same whether it is read off its source declaration or "
                        + "asked of the instance Publication actually holds. A disagreement means the census would "
                        + "drive a provider through the wrong failure semantics - a contained after-commit observer "
                        + "and a fail-closed pre-commit screen arrive through the very same `uses` clause.")
                .isEqualTo(fromSource);
        assertThat(fromRuntime.values()).as("both roles are represented in this graph, or the split proves nothing")
                .contains(Role.AFTER_COMMIT_OBSERVER, Role.PUBLISH_INTERCEPTOR);
    }

    @Test
    void every_fixture_is_keyed_to_the_role_its_own_instance_declares() {
        for (PublicationHookFixture fixture : FIXTURES) {
            Object hook = fixture.create();
            assertThat(fixture.role())
                    .as("the '%s' fixture's role is derived from its instance, never declared", fixture.hook())
                    .isEqualTo(Role.of(hook));
            assertThat(hook.getClass().getName())
                    .as("the '%s' fixture drives the provider class it names", fixture.hook())
                    .isEqualTo(fixture.providerClass());
            assertThat(fixture.create())
                    .as("every simulated process gets its own instance, or a crash check would silently keep an "
                            + "in-memory accumulation the crash was supposed to destroy")
                    .isNotSameAs(fixture.create());
        }
        assertThat(FIXTURES).extracting(PublicationHookFixture::role)
                .as("every role the kit knows about has an archetype, or one contract is asserted nowhere")
                .containsAll(EnumSet.allOf(Role.class));
    }

    @Test
    void publications_own_split_drives_the_discovered_hooks_by_role() throws IOException {
        // The product's split, end to end: not `instanceof` as the kit computes it, but what Publication does with
        // the ONE discovered list. The screens must reach the verdict chain (a committed row exists only there) and
        // every hook must reach the after-commit notify - while a screen that did not override the inherited
        // onPublished must NOT appear as an observer, which is what the empty override buys.
        ArtifactStore store = store("discovered-split");
        ArtifactDescriptor artifact = ArtifactDescriptor.at("kit", "/kit/discovered");
        Publication publication = new Publication(store);

        Publication.Commit committed = publication.commit(artifact,
                new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
                Publication.Republish.overwrite(), _ -> Publication.Visibility.at(artifact.path()));

        assertThat(committed.visible()).as("the discovered chain accepts an unarranged upload").isTrue();
        String slug = Keys.slug(artifact.path());
        assertThat(store.readVersioned(RecordingScreen.COMMITTED + "/" + slug))
                .as("the recording screen was driven through the VERDICT chain - a committed row is reachable no "
                        + "other way, so this is Publication's own instanceof split at work")
                .isPresent();
        assertThat(store.readVersioned(WithholdingScreen.AUDIT + "/" + slug).isPresent()).isTrue();
        assertThat(store.readVersioned(AuditingScreen.COMMITTED + "/" + slug).isPresent()).isTrue();
        assertThat(store.readVersioned(IndexObserver.SPACE + "/" + slug))
                .as("and the plain observers were driven through the after-commit notify").isPresent();
        assertThat(store.readVersioned(OutboxObserver.PENDING + "/" + slug).isPresent()).isTrue();
        assertThat(store.readVersioned(AuditingScreen.OBSERVED + "/" + slug))
                .as("a screen that DOES override the inherited onPublished rides the after-commit call too")
                .isPresent();
        assertThat(store.readVersioned(RecordingScreen.SEEN + "/" + slug).isPresent()).isTrue();

        // ... and the screens that did not override it left no observer trace, so the interceptors riding the
        // observer list never double-count themselves.
        assertThat(ObserverLeg.overriddenBy(new RecordingScreen()))
                .as("a screen with no observer leg of its own is not an observer of its own verdict").isEmpty();
        assertThat(ObserverLeg.overriddenBy(new AuditingScreen()))
                .containsExactly(ObserverLeg.ON_PUBLISHED);
        assertThat(ObserverLeg.overriddenBy(new IndexObserver()))
                .as("a plain observer overrides the legs it actually implements")
                .containsExactlyInAnyOrder(ObserverLeg.ON_PUBLISHED, ObserverLeg.ON_DELETED);
    }

    @Test
    void the_discovered_chain_is_loaded_once_and_cached_for_the_process() throws IOException {
        // Clause 10: instances are ServiceLoader-discovered once at Publication class load and cached for the life of
        // the process - which is why a screen that owns a thread or a client owns it for the process lifetime.
        ArtifactStore store = store("cached-chain");
        int before = RecordingScreen.CONSTRUCTIONS.get();
        for (int round = 0; round < 5; round++) {
            new Publication(store);
        }
        assertThat(RecordingScreen.CONSTRUCTIONS.get())
                .as("constructing five Publications must construct no further screens: the chain is discovered once "
                        + "at class load, not re-resolved per publication, so there is no close hook and no "
                        + "per-request lifecycle to reason about")
                .isEqualTo(before);
    }

    // --- the fixtures cover the contract --------------------------------------------------------------------------

    @Test
    void every_contract_property_is_exercised_by_some_fixture() {
        Set<PublicationHookContract.Property> exercised = FIXTURES.stream()
                .flatMap(fixture -> PublicationHookContract.checks(fixture).stream())
                .map(PublicationHookContract.Check::property)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PublicationHookContract.Property.class)));

        assertThat(exercised)
                .as("a property every fixture excludes is asserted nowhere; the contract may not shrink by attrition. "
                        + "Either a hook must run it, or the property does not belong in the contract.")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PublicationHookContract.Property.class));

        // ... and every crash point the kit knows about really is one of those properties, so a window cannot be added
        // to the enum and then never injected.
        assertThat(EnumSet.allOf(PublicationHookContract.CrashPoint.class))
                .extracting(PublicationHookContract.CrashPoint::property)
                .as("every crash point proves a contract property")
                .allSatisfy(property -> assertThat(exercised).contains(property));

        for (PublicationHookFixture fixture : FIXTURES) {
            fixture.unsupported().forEach((property, reason) ->
                    assertThat(reason).as("the '%s' fixture's exclusion of %s", fixture.hook(), property)
                            .isNotBlank());
        }
    }

    @Test
    void every_supported_delivery_class_is_represented_by_an_observer_fixture() {
        Set<Delivery> declared = FIXTURES.stream()
                .filter(PublicationHookFixture.Observer.class::isInstance)
                .map(fixture -> ((PublicationHookFixture.Observer) fixture).delivery())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Delivery.class)));

        assertThat(declared)
                .as("every delivery class this seam supports needs an archetype, or the kit stops distinguishing "
                        + "between them and silently holds one class to another's guarantee")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Delivery.class).stream()
                        .filter(Delivery::supported).collect(Collectors.toSet()));
        assertThat(Delivery.COMMIT_COUPLED_AT_LEAST_ONCE.supported())
                .as("and the unsupported class stays unsupported until T-107 proves a pre-commit intent machine at "
                        + "every crash point - writing an outbox inside an after-commit callback is not that")
                .isFalse();
    }

    // --- the out-of-graph inventory T-205b inherits ----------------------------------------------------------------

    @Test
    void the_out_of_graph_hooks_stay_named_and_role_keyed() {
        assertThat(OUT_OF_GRAPH)
                .as("every hook this graph cannot reach is named WITH the role its downstream source declares, "
                        + "because that role is what decides which legs T-205b's fixture must run")
                .hasSize(14);
        assertThat(OUT_OF_GRAPH.values().stream().filter(Role.PUBLISH_INTERCEPTOR::equals).count())
                .as("the three pre-commit screens riding the one `uses PublicationObserver` clause").isEqualTo(3);
        assertThat(OUT_OF_GRAPH.values().stream().filter(Role.PRE_COMMIT_RELEASE_HOOK::equals).count())
                .as("the four hold-release hooks, which are not PublicationObservers at all").isEqualTo(4);
        assertThat(OUT_OF_GRAPH.values().stream().filter(Role.AFTER_COMMIT_OBSERVER::equals).count())
                .as("and the seven contained after-commit observers").isEqualTo(7);
        assertThat(OUT_OF_GRAPH.keySet())
                .as("no out-of-graph entry may name a hook this graph CAN reach - that would be a fixture dodge")
                .doesNotContainAnyElementsOf(discovered().stream().map(Provider::implementation).toList());
    }

    // --- negative controls ------------------------------------------------------------------------------------------

    @Test
    void the_census_trips_when_a_leg_is_broken() throws IOException {
        List<Provider> declared = declared();
        List<Provider> discovered = discovered();
        List<String> fixtures = discoverableFixtures();

        assertThatThrownBy(() -> ContractCensus.of(PublicationObserver.class, declared, discovered,
                fixtures.subList(0, fixtures.size() - 1), EXEMPTIONS))
                .as("dropping one fixture must fail the census - the ratchet's whole purpose")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither fixture nor exemption");

        assertThatThrownBy(() -> ContractCensus.of(PublicationObserver.class, declared,
                discovered.subList(0, discovered.size() - 1), fixtures, EXEMPTIONS))
                .as("a declared hook the runtime graph cannot see must fail even though ServiceLoader is happy - the "
                        + "blind spot a discovery-only census has")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover");

        assertThatThrownBy(() -> ContractCensus.of(PublicationObserver.class,
                declared.subList(0, declared.size() - 1), discovered, fixtures, EXEMPTIONS))
                .as("a runtime hook no source or kit clause declares must fail too")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare");
    }

    @Test
    void a_fixture_may_not_declare_a_role_its_instance_contradicts() {
        // The defect this kit exists to make impossible: a screen registered as an after-commit observer would be run
        // through the CONTAINED legs, and would pass while failing open.
        PublicationHookFixture liar = new AuditingScreenFixture() {
            @Override
            public Role role() {
                return Role.AFTER_COMMIT_OBSERVER;
            }
        };
        assertThatThrownBy(() -> PublicationHookContract.checks(liar))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The role is not a fixture's to choose");

        // ... and the mirror: a fixture whose sub-interface does not match its instance cannot be asked the questions
        // its real role needs answered.
        PublicationHookFixture mismatched = new MisregisteredScreen();
        assertThatThrownBy(() -> PublicationHookContract.checks(mismatched))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not implement Interceptor");
    }

    @Test
    void a_fixture_may_not_claim_commit_coupled_at_least_once() {
        PublicationHookFixture overclaiming = new IndexObserverFixture() {
            @Override
            public Delivery delivery() {
                return Delivery.COMMIT_COUPLED_AT_LEAST_ONCE;
            }
        };
        assertThatThrownBy(() -> PublicationHookContract.checks(overclaiming))
                .as("gate 5: no fixture may declare a delivery class the commit protocol does not provide, and the "
                        + "refusal names the ticket that could change it")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("T-107");
    }

    @Test
    void an_exclusion_without_a_reason_is_refused() {
        PublicationHookFixture silent = new IndexObserverFixture() {
            @Override
            public Map<PublicationHookContract.Property, String> unsupported() {
                return Collections.singletonMap(
                        PublicationHookContract.Property.A_DUPLICATE_DELIVERY_CONVERGES, "  ");
            }
        };
        assertThatThrownBy(() -> PublicationHookContract.checks(silent))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("without a reason");
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** A screen registered through the wrong sub-fixture, so the kit's refusal has something real to refuse. */
    private static final class MisregisteredScreen implements PublicationHookFixture {

        @Override
        public String hook() {
            return "misregistered";
        }

        @Override
        public String providerClass() {
            return AuditingScreen.class.getName();
        }

        @Override
        public Object create() {
            return new AuditingScreen();
        }

        @Override
        public List<String> namespaces() {
            return List.of(AuditingScreen.SPACE);
        }

        @Override
        public Map<String, String> projection(ArtifactStore store) {
            return Map.of();
        }
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? directory.toString() : null);
    }

    /** The role a provider's own source declares - the static half of the split, read the way T-002's inventory reads
     *  it: a provider is keyed to the role interface it names among its supertypes. */
    private static Role roleInSource(String providerClass) throws IOException {
        Path source = repositoryRoot().resolve("test").resolve("publication")
                .resolve(providerClass.replace('.', '/') + ".java");
        if (!Files.isRegularFile(source)) {
            throw new AssertionError(providerClass + " has no source file under test/publication, so keying it to a "
                    + "role would silently key it to nothing: " + source);
        }
        String stripped = COMMENTS.matcher(Files.readString(source)).replaceAll("");
        String simple = providerClass.substring(providerClass.lastIndexOf('.') + 1);
        Matcher declaration = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?class\\s+"
                + Pattern.quote(simple) + "\\b([^{]*)\\{", Pattern.DOTALL).matcher(stripped);
        if (!declaration.find()) {
            throw new AssertionError("cannot read the type declaration of " + providerClass + " out of " + source);
        }
        String supertypes = declaration.group(1);
        if (supertypes.contains(PublishInterceptor.class.getSimpleName())) {
            return Role.PUBLISH_INTERCEPTOR;
        }
        if (supertypes.contains(PublicationObserver.class.getSimpleName())) {
            return Role.AFTER_COMMIT_OBSERVER;
        }
        return Role.PRE_COMMIT_RELEASE_HOOK;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("store").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
