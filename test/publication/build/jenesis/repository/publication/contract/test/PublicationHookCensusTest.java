package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.ChoreographyMutant;
import build.jenesis.repository.store.testkit.Falsification;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.Mutant;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Delivery;
import build.jenesis.repository.store.testkit.PublicationHookFixture.ObserverLeg;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Role;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            new IndexObserverFixture(), new FeedSplittingObserverFixture(), new OutboxObserverFixture(),
            new RecordingScreenFixture(),
            new WithholdingScreenFixture(), new AuditingScreenFixture(), new OverrideHookFixture(),
            new MavenMetadataObserverFixture(), new OciListingObserverFixture(), new RawListingObserverFixture());

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
     * <p>All fourteen are <b>the earlier</b> to fixture. The split matters: the three interceptors and the four
     * hold-release hooks are pre-commit and fail-closed, and running any of them through the after-commit legs would
     * assert the opposite of their contract.
     */
    private static final Map<String, Role> OUT_OF_GRAPH = Map.ofEntries(
            // ENT after-commit observers - contained, best-effort, repaired by the walk (the earlier migration candidates)
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

    @TempDir
    Path root;

    // --- the static and runtime legs, separately ---------------------------------------------------------------

    private static List<Provider> declared() throws IOException {
        // Two roots, and the pair is the point. `source/` is the shipped inventory - the thing that must never grow a
        // hook without a fixture. This module is where the kit's own archetypes are declared, so the census has a
        // non-empty population to verify while the shipped one is empty. The day a source module provides a hook, the
        // static leg grows and the runtime leg fails until this module requires it: gate 1, working as intended.
        // One call, where there were two: the resolved graph carries the shipped observers and this module's own
        // fixture alike, so there is no source root to name and no way for the two halves to disagree.
        return ContractCensus.declaredProviders(PublicationObserver.class);
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
    void every_shipped_hook_has_a_fixture() throws IOException {
        // The shipped inventory: the three stored-listing observers of the free formats (Maven's computed
        // maven-metadata.xml, the OCI tag lists and catalog, the raw directory pages). A source module that provides a
        // further PublicationObserver must bring a PublicationHookFixture - an Observer one with its delivery class
        // and repair leg, or an Interceptor one with its verdicts and the keys its verdict reads - require its module
        // here, and join FIXTURES.
        assertThat(shipped()).extracting(Provider::implementation)
                .as("every shipped hook is keyed to a fixture")
                .allSatisfy(implementation -> assertThat(FIXTURES).extracting(PublicationHookFixture::providerClass)
                        .contains(implementation));
    }


    /** The hooks the PRODUCT ships: every declared observer except the ones a test module contributes. */
    private static List<Provider> shipped() {
        return ContractCensus.declaredProviders(PublicationObserver.class,
                module -> !module.getName().endsWith(".test"));
    }

    // --- the role split, derived three ways --------------------------------------------------------------------

    /**
     * The role split, asked of the instances the graph actually holds.
     *
     * <p>This used to derive each role a second time by reading the {@code implements} clause out of the provider's
     * source text and comparing the two. That re-derivation was the only source scan in this census, and when the
     * fifteen format observers moved onto {@link build.jenesis.repository.store.ListingObserver} - a
     * {@code PublicationObserver} sub-interface - it silently re-keyed every one of them to the fail-closed
     * pre-commit role and failed the comparison. The scan was wrong and the instance was right, which is the
     * general case: {@code Role.of} asks the object, and an object cannot misreport the interfaces it implements.
     *
     * <p>So the leg is gone rather than taught about one more supertype, because there was no invariant behind it
     * that the instance does not already settle. What a declaration says is still checked - by the compiler, which
     * will not let a class be provided as a service it does not implement.
     */
    @Test
    void both_roles_are_represented_in_the_graph_the_runtime_discovers() {
        Map<String, Role> discovered = new TreeMap<>();
        for (ServiceLoader.Provider<PublicationObserver> provider
                : ServiceLoader.load(PublicationObserver.class).stream().toList()) {
            discovered.put(provider.type().getName(), Role.of(provider.get()));
        }
        assertThat(discovered).as("the one discovered clause carries hooks at all").isNotEmpty();
        assertThat(discovered.values())
                .as("both roles are represented in this graph, or the split proves nothing: a contained "
                        + "after-commit observer and a fail-closed pre-commit screen arrive through the very same "
                        + "`uses` clause, and a census over only one of them would assert the wrong contract for "
                        + "the other")
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
                .as("and the unsupported class stays unsupported until proves a pre-commit intent machine at "
                        + "every crash point - writing an outbox inside an after-commit callback is not that")
                .isFalse();
    }

    // --- the falsification declaration ---------------------------------------------------------------------

    /**
     * Which arranged commit choreography falsifies each clause that is about {@link Publication} rather than about a
     * hook. This map is the answer to the finding recorded and was raised to close: twenty of the
     * kit's forty-six clauses are the choreography's, the fixture's hook is a bystander in them, and until now
     * <b>the falsification leg proved things about implementations and said nothing about the choreography they plug
     * into</b> - which is where several of this plan's crash-window claims live.
     *
     * <p>Nineteen of the twenty now carry a {@link ChoreographyMutant}, and
     * {@link #every_choreography_clause_is_falsified_by_the_arrangement_it_names()} runs each pairing and requires the
     * check to say otherwise. Read {@link ChoreographyMutant}'s own documentation for what that proves and what it
     * does not: the arrangement produces the observable a mutated {@code Publication} would produce, which makes the
     * check demonstrably discriminating on the clause, but it is a faithful simulation of the defect rather than the
     * defect itself. {@code Publication} is {@code final} on purpose and stays that way - the argument is in its
     * javadoc, where a reader who wonders why they cannot substitute it meets it.
     */

    /**
     * The contract properties nothing this kit can substitute falsifies - <b>one, since the earlier work</b>, and it is the one
     * where there is no observable to arrange because nothing the kit hands {@code Publication} is ever invoked.
     *
     * <p>The list used to hold twenty, all with the same reason: the clause is about {@link Publication}'s own commit
     * choreography, {@code Publication} is a {@code final} core class the kit constructs and cannot substitute,
     * and the checks assert it with kit-owned probe screens while the fixture's hook rides along as a bystander.
     * That reason was true and it stayed true - what changed is that {@link ChoreographyMutant} arranges the hooks the
     * kit <em>does</em> control so the choreography produces the observable a mutated {@code Publication} would, which
     * reaches nineteen of them. This one it cannot reach: the crash lands before the chain runs at all, so there is no
     * hook call to arrange and what the window leaves is the store's and {@code Publication}'s alone.
     */

    /**
     * The (hook, property) pairs where the property IS falsifiable in general but this hook's own shape puts the
     * mutation out of reach - the honest edge of the leg's per-fixture coverage, and a shorter list than it looks
     * because the three interceptor archetypes deliberately divide the clauses between them.
     */
    /** Why the stored-listing observers of the shipped formats are not falsified on the recording clauses. */
    private static final String LISTING_HOOK = "a stored-listing observer records nothing on a publish - the "
            + "format's own publish writes the listing - and under this kit, whose 'kit' artifacts belong to no "
            + "format, it writes nothing on any leg; the clauses about what it records are proven by the format "
            + "contract's held-version and revalidation legs over the format's own paths (FormatContract), where a "
            + "listing that failed to retract or to converge fails visibly.";

    private static final Map<String, String> NOT_THIS_HOOKS_TO_FALSIFY = Map.ofEntries(
            Map.entry("kit-recording-screen / A_LATER_VERDICT_RETRACTS_WITHOUT_A_POINTER_REWRITE",
                    "this screen votes at publish time and has no read side, so the check drives the kit's own withholding "
                    + "probe and the fixture's screen is a bystander in the retraction. WithholdingScreen is the "
                    + "archetype that owns this clause and is falsified on it."),
            Map.entry("kit-auditing-screen / A_LATER_VERDICT_RETRACTS_WITHOUT_A_POINTER_REWRITE",
                    "the same, and more so: this screen renders no verdict of its own at all."),
            Map.entry("kit-auditing-screen / A_SCREEN_DOES_NOT_CATCH_ITS_OWN_STORE_FAILURE_INTO_AN_ACCEPT",
                    "it declares no verdict-bearing read, so there is no read to fault and the check asserts the shape "
                    + "(no read implies no non-ACCEPT verdict) rather than a behaviour. RecordingScreen and "
                    + "WithholdingScreen both declare reads and are falsified on this clause."),
            Map.entry("kit-withholding-screen / ONE_INSTANCE_SERVES_CONCURRENT_PUBLISHES_AND_READS",
                    "it can reach exactly one verdict, so per-call state in a field would answer the same thing whatever it "
                    + "remembered - there is nothing for a concurrent publish to be confused with. RecordingScreen "
                    + "reaches all three and is falsified on it."),
            Map.entry("kit-auditing-screen / ONE_INSTANCE_SERVES_CONCURRENT_PUBLISHES_AND_READS",
                    "the same one-verdict shape."),
            Map.entry("maven-metadata-listing / A_DUPLICATE_DELIVERY_CONVERGES",
                    LISTING_HOOK),
            Map.entry("maven-metadata-listing / A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED",
                    LISTING_HOOK),
            Map.entry("maven-metadata-listing / THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE",
                    LISTING_HOOK),
            Map.entry("oci-listing / A_DUPLICATE_DELIVERY_CONVERGES",
                    LISTING_HOOK),
            Map.entry("oci-listing / A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED",
                    LISTING_HOOK),
            Map.entry("oci-listing / THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE",
                    LISTING_HOOK),
            Map.entry("raw-listing / A_DUPLICATE_DELIVERY_CONVERGES",
                    LISTING_HOOK),
            Map.entry("raw-listing / A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED",
                    LISTING_HOOK),
            Map.entry("raw-listing / THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE",
                    LISTING_HOOK));

    @Test
    void every_property_a_hook_owns_declares_the_mutation_that_must_break_it() {
        Set<PublicationHookContract.Property> declaring = PublicationHookContract.mutations().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PublicationHookContract.Property.class)));
        Set<PublicationHookContract.Property> undeclared =
                EnumSet.allOf(PublicationHookContract.Property.class);
        undeclared.removeAll(declaring);

        Set<PublicationHookContract.Property> choreography = EnumSet.copyOf(PublicationHookContract.choreography().keySet());
        choreography.addAll(PublicationHookContract.unfalsifiable().keySet());
        assertThat(undeclared)
                .as("a property that names no mutation is a property nothing proves could have said otherwise - the "
                        + "vacuity exists to close. It may only be left undeclared when the clause is about "
                        + "Publication rather than about the hook, and then it owes either an arranged choreography "
                        + "that falsifies it (PublicationHookContract.choreography()) or a reason on the reviewed PublicationHookContract.unfalsifiable() list.")
                .isEqualTo(choreography);
        assertThat(PublicationHookContract.choreography().keySet())
                .as("a clause cannot be both arranged and unfalsifiable: the two lists are a partition of the "
                        + "choreography half, so an entry that gained an arrangement must lose its exemption")
                .doesNotContainAnyElementsOf(PublicationHookContract.unfalsifiable().keySet());
        PublicationHookContract.unfalsifiable().values().forEach(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void every_choreography_clause_is_falsified_by_the_arrangement_it_names() throws Exception {
        // the earlier leg. Each clause about Publication's own commit sequence is re-run under the arranged choreography
        // it names - a chain that stops at the first REJECT, a committed that skips the neutral verdict, a review
        // pointer that is gone when committed fires - and must say otherwise. It is run for EVERY fixture the clause
        // binds to, not for one representative, because the checks divide the interceptor clauses between three screen
        // archetypes and a pairing that only bites for one of them is a pairing that covers one third of the kit.
        List<String> survived = new ArrayList<>();
        for (PublicationHookFixture fixture : FIXTURES) {
            for (PublicationHookContract.Check check : PublicationHookContract.checks(fixture)) {
                ChoreographyMutant arrangement = PublicationHookContract.choreography().get(check.property());
                if (arrangement == null) {
                    continue;
                }
                try {
                    Falsification.requireBrokenByChoreography(fixture, check, arrangement, this::faulting);
                } catch (AssertionError unfalsified) {
                    survived.add(fixture.hook() + " / " + check.property() + " under " + arrangement + ": "
                            + String.valueOf(unfalsified.getMessage()).lines().findFirst().orElse(""));
                }
            }
        }
        Collections.sort(survived);
        assertThat(survived)
                .as("""
                        these clauses are about Publication's own choreography, and the arrangement each one names \
                        produces exactly the observable a Publication that had lost that behaviour would produce. \
                        Each line below is one of two outcomes, and the message says which: 'PASSED under' means the \
                        check stayed green and is therefore not measuring the clause at all (the pairing is wrong, or \
                        the check has stopped reading the probe it asserts on), while 'it broke' means the \
                        arrangement took the harness out from under the check - which proves nothing either way and \
                        may not be banked as a red.%n%s""",
                        String.join(System.lineSeparator(), survived))
                .isEmpty();
    }

    @Test
    void the_arranged_choreographies_are_all_used_and_the_ordinary_leg_runs_under_none() throws Exception {
        // Two mirrors, and both are needed. An arrangement no clause names is a defect nothing applies - the same
        // blind spot as a mutant no property declares. And the ordinary leg must run under NONE, or every green in
        // this kit would be a green about an arranged choreography rather than about the product's.
        Set<ChoreographyMutant> arranged = EnumSet.copyOf(PublicationHookContract.choreography().values());
        Set<ChoreographyMutant> vocabulary = EnumSet.allOf(ChoreographyMutant.class);
        vocabulary.remove(ChoreographyMutant.NONE);
        assertThat(arranged)
                .as("an arranged choreography no clause names is never driven, so it proves nothing and cannot rot "
                        + "honestly - the mirror of the leg above")
                .containsExactlyInAnyOrderElementsOf(vocabulary);

        assertThat(faulting("choreography-default").choreography())
                .as("a store built the way every ordinary check gets one must run the product's own choreography, or "
                        + "the whole kit would be measuring an arrangement")
                .isEqualTo(ChoreographyMutant.NONE);
    }

    @Test
    void every_mutant_in_the_vocabulary_is_declared_by_some_property() {
        Set<Mutant> declared = PublicationHookContract.mutations().values().stream()
                .flatMap(List::stream)
                .map(PublicationHookContract.Mutation::mutant)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Mutant.class)));

        Set<Mutant> vocabulary = EnumSet.allOf(Mutant.class);
        vocabulary.remove(Mutant.NONE);                       // the identity: the unmutated leg every fixture runs
        assertThat(declared)
                .as("a mutant no property declares is never applied, so it proves nothing and cannot rot honestly - "
                        + "the mirror of the property leg above, and the reason the vocabulary may not grow a "
                        + "constant somebody meant to use")
                .containsExactlyInAnyOrderElementsOf(vocabulary);
    }

    @Test
    void every_declared_mutation_is_applied_to_some_fixture_on_this_graph() {
        // A mutation is declared with a predicate over the fixture, so one whose predicate no landed fixture satisfies
        // is a declaration that never runs - the same blind spot as an exemption naming a hook that no longer exists.
        Map<PublicationHookContract.Property, List<Mutant>> unapplied =
                new EnumMap<>(PublicationHookContract.Property.class);
        PublicationHookContract.mutations().forEach((property, mutations) -> {
            List<Mutant> never = mutations.stream()
                    .map(PublicationHookContract.Mutation::mutant)
                    .filter(mutant -> FIXTURES.stream().noneMatch(fixture ->
                            PublicationHookContract.checks(fixture).stream()
                                    .anyMatch(check -> check.property() == property)
                            && PublicationHookContract.mutations(fixture, property).stream()
                                    .anyMatch(applicable -> applicable.mutant() == mutant)))
                    .toList();
            if (!never.isEmpty()) {
                unapplied.put(property, never);
            }
        });
        assertThat(unapplied)
                .as("every declared mutation must really be run against at least one fixture here: a mutation whose "
                        + "predicate no landed hook satisfies is a falsification leg that exists only on paper")
                .isEmpty();
    }

    @Test
    void the_legs_no_mutation_reaches_for_a_given_hook_are_a_reviewed_list() {
        List<String> unreached = new ArrayList<>();
        for (PublicationHookFixture fixture : FIXTURES) {
            for (PublicationHookContract.Check check : PublicationHookContract.checks(fixture)) {
                if (PublicationHookContract.unfalsifiable().containsKey(check.property()) || PublicationHookContract.choreography().containsKey(check.property())) {
                    continue;                       // the choreography half - argued or arranged, once for every hook
                }
                if (PublicationHookContract.mutations(fixture, check.property()).isEmpty()) {
                    unreached.add(fixture.hook() + " / " + check.property());
                }
            }
        }
        Collections.sort(unreached);
        assertThat(unreached)
                .as("these legs run as ordinary checks but carry no falsification for THIS hook, because a "
                        + "mutation's predicate excluded it. That is legitimate - the three screen archetypes divide "
                        + "the interceptor clauses between them on purpose - but it is the kind of gap that grows "
                        + "silently, so each pair is argued here and a new one has to be argued too.")
                .containsExactlyElementsOf(NOT_THIS_HOOKS_TO_FALSIFY.keySet().stream().sorted().toList());
        NOT_THIS_HOOKS_TO_FALSIFY.values().forEach(reason -> assertThat(reason).isNotBlank());
    }

    /**
     * <b>The kit's own lens, executed rather than declared.</b> Every check of every fixture is run once more
     * against a hook that is a no-op from end to end - the shape every hand-run mutation pass in this plan has found -
     * and the survivors are required to be covered some other way: by a <em>targeted</em> mutation this fixture runs,
     * or by one of the two reviewed lists above.
     *
     * <p>It is derived rather than pinned to a literal, because the honest answer is large and moves with the kit:
     * on this graph a hook that does nothing at all passes <b>101 of the 114 checks</b>, and almost all of them for
     * the same reason {@link PublicationHookContract#choreography()} gives - the clause is Publication's, and the hook is a bystander in it.
     * What must never happen is a check that an inert hook survives AND that nothing else falsifies, because that
     * check is proven over nothing at all; that is what this leg refuses.
     *
     * <p><b>The population this figure is measured over is worth naming</b>, because it is not the product's. The free
     * core ships no hook at all, so every fixture here is a synthetic archetype the kit invented. The number that says
     * what this contract proves about the <em>shipped</em> hooks would have to be measured in the edition that has
     * them, and today is not - which is a recorded defect rather than a gap in this file.
     */
    @Test
    void every_check_an_inert_hook_survives_is_falsified_some_other_way() throws Exception {
        List<String> unguarded = new ArrayList<>();
        for (PublicationHookFixture fixture : FIXTURES) {
            for (PublicationHookContract.Check check : PublicationHookContract.checks(fixture)) {
                if (!survivesAnInertHook(fixture, check)) {
                    continue;                                   // the general probe already catches this one
                }
                if (PublicationHookContract.unfalsifiable().containsKey(check.property()) || PublicationHookContract.choreography().containsKey(check.property())
                        || NOT_THIS_HOOKS_TO_FALSIFY.containsKey(fixture.hook() + " / " + check.property())) {
                    continue;                                   // argued above, or falsified by an arrangement
                }
                boolean targeted = PublicationHookContract.mutations(fixture, check.property()).stream()
                        .anyMatch(mutation -> mutation.mutant() != Mutant.NO_WORK_AT_ALL);
                if (!targeted) {
                    unguarded.add(fixture.hook() + " / " + check.property());
                }
            }
        }
        Collections.sort(unguarded);
        assertThat(unguarded)
                .as("a hook that does nothing at all passes these checks, and no targeted mutation catches them "
                        + "either - so nothing in the kit distinguishes a compliant hook from one that never ran. "
                        + "That is exactly the shape hit by hand. Give the property a mutation that removes "
                        + "the behaviour it is really about, or argue the pair onto PublicationHookContract.unfalsifiable() / "
                        + "NOT_THIS_HOOKS_TO_FALSIFY.%n%s", String.join(System.lineSeparator(), unguarded))
                .isEmpty();
    }

    @Test
    void the_inert_hook_probe_really_bites_on_every_role() throws Exception {
        // The mirror, and the reason the leg above is not vacuous: if NOTHING anywhere caught an inert hook, the
        // survivor set would be every check and the leg above would be reduced to reading the reviewed lists back to
        // itself. Each role must have at least one check that an inert hook demonstrably fails.
        Set<Role> biting = EnumSet.noneOf(Role.class);
        for (PublicationHookFixture fixture : FIXTURES) {
            for (PublicationHookContract.Check check : PublicationHookContract.checks(fixture)) {
                if (!survivesAnInertHook(fixture, check)) {
                    biting.add(fixture.role());
                }
            }
        }
        assertThat(biting)
                .as("every role must have at least one check a hook that is a no-op throughout demonstrably fails, "
                        + "or that role's whole contract can be satisfied by a hook that never ran")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Role.class));
    }

    /** Whether {@code check} passes against a hook that does nothing at all - run, not inferred. A mutant that broke
     *  the check's machinery counts as caught rather than survived, because a check that cannot even be driven over
     *  an inert hook is not one an inert hook silently passes. */
    private boolean survivesAnInertHook(PublicationHookFixture fixture, PublicationHookContract.Check check)
            throws Exception {
        try {
            Falsification.run(fixture, check, Mutant.NO_WORK_AT_ALL, this::faulting);
            return true;
        } catch (AssertionError | RuntimeException | IOException caught) {
            return false;
        }
    }

    @Test
    void the_falsification_leg_trips_when_a_check_survives_its_mutation() throws IOException {
        // The mechanism's own leg, held the way the_census_trips_when_a_leg_is_broken holds the census (and for the
        // same reason): a runner that only ever ran the real checks would be the one part of the kit nothing
        // falsifies. Three synthetic bodies, three outcomes. Which mutant carries them does not matter - the bodies
        // ignore the hook entirely, because what is under test is the runner's verdict rather than a property.
        PublicationHookFixture fixture = FIXTURES.getFirst();
        PublicationHookContract.Mutation mutation = new PublicationHookContract.Mutation(Mutant.NO_WORK_AT_ALL,
                "the synthetic mutation this leg is written against");
        Falsification.Deployment deployment = this::faulting;

        assertThatThrownBy(() -> Falsification.requireBroken(fixture,
                check("a check that asserts nothing", (_, _) -> { }), mutation, deployment))
                .as("a check that asserts nothing survives every mutation, and that is exactly what must be reported "
                        + "- the whole point of the leg")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PASSED against NO_WORK_AT_ALL");

        assertThatCode(() -> Falsification.requireBroken(fixture,
                check("a check that says otherwise", (_, _) -> {
                    throw new AssertionError("the mutated hook did not record the publish");
                }), mutation, deployment))
                .as("and a check that does say otherwise is accepted, so the leg cannot be satisfied by failing "
                        + "everything")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> Falsification.requireBroken(fixture,
                check("a check the mutant breaks rather than falsifies", (_, _) -> {
                    throw new IOException("the store went away");
                }), mutation, deployment))
                .as("a mutant that takes the harness out from under a check proves nothing about whether the check "
                        + "measures its property, so it may not be banked as a red")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("it broke");
    }

    @Test
    void the_choreography_leg_trips_when_a_check_survives_its_arrangement() throws IOException {
        // The same three outcomes for the runner, and for the same reason: the arranged-choreography leg is
        // otherwise the one part of the kit nothing falsifies, which is exactly the shape this whole family is about.
        PublicationHookFixture fixture = FIXTURES.getFirst();
        ChoreographyMutant arrangement = ChoreographyMutant.A_CHAIN_THAT_STOPS_AT_THE_FIRST_REJECT;
        Falsification.Deployment deployment = this::faulting;

        assertThatThrownBy(() -> Falsification.requireBrokenByChoreography(fixture,
                check("a check that asserts nothing", (_, _) -> { }), arrangement, deployment))
                .as("a check that asserts nothing survives every arrangement, and that is what must be reported")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PASSED under A_CHAIN_THAT_STOPS_AT_THE_FIRST_REJECT");

        assertThatCode(() -> Falsification.requireBrokenByChoreography(fixture,
                check("a check that says otherwise", (_, _) -> {
                    throw new AssertionError("every screen behind the rejection was still asked");
                }), arrangement, deployment))
                .as("and a check that does say otherwise is accepted, so the leg cannot be satisfied by failing "
                        + "everything")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> Falsification.requireBrokenByChoreography(fixture,
                check("a check the arrangement breaks rather than falsifies", (_, _) -> {
                    throw new IOException("the store went away");
                }), arrangement, deployment))
                .as("an arrangement that takes the harness out from under a check proves nothing about whether the "
                        + "check measures the clause, and may not be banked as a red")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("it broke");
    }

    /** A synthetic check for the leg above. Its property is arbitrary - nothing here reads it - because what is under
     *  test is the runner's verdict on a body, not the contract's declarations. */
    private static PublicationHookContract.Check check(String name, PublicationHookContract.Body body) {
        return new PublicationHookContract.Check(
                PublicationHookContract.Property.THE_HOOK_STAYS_INSIDE_ITS_DECLARED_NAMESPACES, name, body);
    }

    private FaultInjectingStore faulting(String name) throws IOException {
        return FaultInjectingStore.wrap(store(name.replaceAll("[^A-Za-z0-9]", "_")));
    }

    // --- the out-of-graph inventory inherits ----------------------------------------------------------------

    @Test
    void the_out_of_graph_hooks_stay_named_and_role_keyed() {
        assertThat(OUT_OF_GRAPH)
                .as("every hook this graph cannot reach is named WITH the role its downstream source declares, "
                        + "because that role is what decides which legs the earlier fixture must run")
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
                .hasMessageContaining("");
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
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            // Standalone the tree is source/; inside an enclosing project it is core/source/.
            if (Files.isDirectory(candidate.resolve("core").resolve("source").resolve("store").resolve("spi"))) {
                return candidate.resolve("core");
            }
            if (Files.isDirectory(candidate.resolve("source").resolve("store").resolve("spi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
