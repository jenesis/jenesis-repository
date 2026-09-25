package build.jenesis.repository.publication.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.store.GatedRepository;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clause the hold-release contract could not state, over the same hold shape and the same real review surface the
 * hold-release fixtures beside it drive.
 *
 * <p>{@link HoldReleaseObserver}'s contract clause 5 is about <b>absence</b>, and absence is the one boundary a
 * {@link build.jenesis.repository.store.testkit.PublicationHookFixture} cannot express: every check the kit runs is a
 * claim about a hook, and this is a claim about what happens when there is no hook. A further fixture would need a further
 * provider, and a provider is exactly what this case does not have. So the fixtures' choreography is reused - the
 * retroactive hold shape from {@link HoldReleaseFixture#hold} (a live release pointer with a
 * {@code publish/quarantine} pointer laid over it and a {@code holds/<kind>/} record beside it), the coordinate
 * {@link HookTestFormat} resolves, and {@link GatedRepository} as the release surface - with the one thing changed
 * that matters: the record's kind is one <em>no installed module answers to</em>, which is what a deployment that has
 * uninstalled or switched off a compliance module looks like from the store's side.
 *
 * <p>What it pins is the asymmetry. Adding a provider is harmless and the kit already proves it
 * ({@code A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD}). Removing one used to be fail-open in both directions at once:
 * the kind-neutral reads answered "nothing holds this", and both callers act on that answer permissively. A hold now
 * outlives its module, and only an operator's explicit release ends it.
 */
class OrphanedHoldTest {

    /** A hold kind no module on this graph provides - the store's-eye view of an uninstalled compliance module. The
     *  real kinds are all installed here (the census beside this requires every one), so the absent one has to be
     *  named rather than found. */
    private static final String DECOMMISSIONED = "decommissioned";

    private static final String PATH = HookTestFormat.PREFIX + "org/example/orphaned";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // The retroactive shape the hold-release fixtures hold with: the version is already serving, and a sweep has laid its
        // review pointer over it and written its own record.
        Publication publication = new Publication(store, List.of(), List.of());
        String hash = publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
        publication.link(PATH, hash);
        publication.link("/quarantine" + PATH, hash);
        Hooks.upsert(store, HoldRecords.key(DECOMMISSIONED, HookTestFormat.ECOSYSTEM, Coordinates.of(PATH),
                HookTestFormat.VERSION), "GHSA-0000-0000-0000");
    }

    @Test
    void a_hold_whose_kind_no_installed_module_answers_to_still_holds() throws IOException {
        assertThat(HoldRecords.installedKinds())
                .as("the kind really is unprovided, or the case proves nothing").doesNotContain(DECOMMISSIONED);

        assertThat(HoldReleaseObserver.anyHolds(store, PATH))
                .as("the durable record answers, not the provider registry").isTrue();
        assertThat(HoldReleaseObserver.heldByAnotherKind(store, PATH, "kev"))
                .as("so an automated release of another kind still may not un-retract it").isTrue();
        assertThat(HoldRecords.orphanedKinds(store, PATH))
                .as("and it is reported as a hold no installed module can re-evaluate")
                .containsExactly(DECOMMISSIONED);

        // The full fan-out over every hook this graph really ships, none owning this record.
        HoldReleaseObserver.released(store, PATH);

        assertThat(HoldReleaseObserver.anyHolds(store, PATH))
                .as("no arrangement of installed hooks releases an absent kind's hold").isTrue();
    }

    @Test
    void an_operator_release_at_the_real_review_surface_ends_it_and_leaves_no_stranded_record() throws IOException {
        new GatedRepository(store).release(PATH);

        assertThat(new Publication(store, List.of(), List.of()).blob("/quarantine" + PATH))
                .as("the review pointer is cleared - a human decided").isEmpty();
        assertThat(HoldRecords.heldKinds(store, PATH))
                .as("and the orphaned record goes with it rather than reading as held forever").isEmpty();
        assertThat(HoldReleaseObserver.anyHolds(store, PATH)).isFalse();
        assertThat(Hooks.rows(store, "overrides/" + DECOMMISSIONED))
                .as("no override is invented in an absent kind's vocabulary - a reinstall re-evaluates from scratch")
                .isEmpty();
    }

    @Test
    void an_operator_discard_at_the_real_review_surface_reaps_it_too() throws IOException {
        new GatedRepository(store).discard(PATH);   // throws IllegalStateException if nothing was held

        assertThat(HoldRecords.heldKinds(store, PATH))
                .as("a discarded version has no published/ sidecar any sweep would ever reach, so the row would "
                        + "otherwise dangle forever").isEmpty();
    }

    /**
     * the earlier half of the same asymmetry, one layer down. {@link OrphanedHoldTest} above is about a hold whose
     * <em>kind</em> has no installed module; this is about a hold whose <em>format</em> has none - the path the record
     * is keyed against can no longer be turned into a coordinate, so the kind-neutral reads had nothing to look under
     * and answered "nothing holds this" for a hold that was standing. Same fail-open shape, same two permissive
     * consumers, and it survived because the record itself was never the problem: the route from the request
     * path to it was.
     *
     * <p>The path below is deliberately one {@link HookTestFormat} does not claim, which is what a deployment that has
     * removed a format module looks like from the store's side. The durable {@code subjects/} record - written where
     * the hold was placed, while that format was by construction still installed - is the route back, and an
     * operator's release at the real review surface reaps it with the hold.
     */
    @Test
    void a_hold_whose_format_no_installed_module_places_still_holds_and_still_releases() throws IOException {
        String unplaceable = "/absent-format/org/example/orphaned-1.0.tgz";
        assertThat(new StoreRepositoryInventory(store).describe(unplaceable))
                .as("no installed format claims that path, or the case proves nothing").isEmpty();
        Publication publication = new Publication(store, List.of(), List.of());
        String hash = publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
        publication.link(unplaceable, hash);   // the retroactive shape: already serving, then held over the top
        HeldSubjects.hold(publication, store, unplaceable, hash, "absent-format", "org.example:orphaned", "1.0");
        Hooks.upsert(store, HoldRecords.key(DECOMMISSIONED, "absent-format", "org.example:orphaned", "1.0"),
                "GHSA-0000-0000-0000");

        assertThat(HoldRecords.heldKinds(store, unplaceable))
                .as("the durable subject record is the route from the path back to the coordinate the hold is keyed "
                        + "by - without it this answered empty and the hold read as released")
                .containsExactly(DECOMMISSIONED);
        assertThat(HoldReleaseObserver.anyHolds(store, unplaceable))
                .as("so the accepted-re-publish guard still treats the path as held").isTrue();
        assertThat(HoldReleaseObserver.heldByAnotherKind(store, unplaceable, "kev"))
                .as("and an automated release of another kind still may not un-retract it").isTrue();

        new GatedRepository(store).release(unplaceable);

        assertThat(HoldRecords.heldKinds(store, unplaceable))
                .as("a human decided, so the orphaned record goes - the only thing that ever ends it").isEmpty();
        assertThat(HeldSubjects.read(store, unplaceable))
                .as("and the subject record is reclaimed with the hold it described").isEmpty();
    }
}
