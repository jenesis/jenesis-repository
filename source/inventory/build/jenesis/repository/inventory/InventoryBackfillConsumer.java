package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * Rebuilds the {@code published} record of a blobs-namespace release whose record was lost, for every format that can
 * name the coordinate one of its pointers serves.
 *
 * <p><strong>The gap this closes.</strong> The reconcile's forward-repair leg walks {@code publish/}, so it covers
 * the Publication-namespace layouts only - Maven and the raw layout. Every blobs-namespace format keeps its serving
 * pointers under its own roots, which that walk never visits, so without this a release of theirs whose row went
 * missing would never be rebuilt. The row goes missing through an ordinary window: the accept path records it
 * <em>after</em> the artifact is committed, so anything stopping the process in between leaves a pointer with no
 * row. The artifact keeps serving and stays enumerable through its own format; what stops is a retroactive sweep
 * seeing it, so it can carry a later-listed CVE while the held gauge reads clean.
 *
 * <p><strong>Why one consumer rather than one per format.</strong> The parse of a pointer key is the only part that
 * differs between formats, and a consumer per format would repeat everything else around its own parse. So the parse
 * lives on the layout as {@link BlobLayout#describePointer} and this walks every pointer once for all of them.
 *
 * <p><strong>A layout that cannot name a key is not a failure.</strong> {@code describePointer} answers empty by
 * default, so a format that has not implemented it is simply not repaired here - which is the honest position,
 * because a layout that answered <em>wrongly</em> would write a row naming the wrong
 * coordinate, and retention ages by that row.
 *
 * <p><strong>Two layouts that disagree stop rather than pick.</strong> Several installed formats may declare one
 * ecosystem, and two do share the {@code oci} root. Where more than one names a coordinate for the same key and
 * they do not agree, this records nothing and says so: taking the first answer would make a durable row a property
 * of module-path ordering, so one node could record a coordinate another would not, with nothing able to report it.
 *
 * <p><strong>A copy is recorded as a copy.</strong> A pointer a pull-through filled is a holding too, and one whose
 * fill was not recorded - its fill's notice lost or never sent - is recorded as the
 * cached copy its origin trail says it is, through the same decision the reconcile's forward leg takes
 * ({@code InventoryReconciler.holdMissing}), rather than as a release that retention would then age.
 *
 * <p><strong>Idempotent by membership.</strong> A pointer whose row already exists - written by the accept path or a
 * fill, by an earlier pass, or by a replayed delivery after a crash-resume - is skipped without a write. So a second pass
 * over unchanged state changes nothing durable and a row's instant never drifts. The instant a first record stamps
 * is the pass's own {@link WalkPass#started()}, which is stable across a resume and identical across fanned-out
 * workers, so two workers racing on one pointer write the same bytes. It is deliberately not a sentinel: the
 * publish instant is what retention ages by, and the pass's start is the most conservative honest answer.
 */
public final class InventoryBackfillConsumer implements WalkConsumer {

    private static final System.Logger LOGGER = System.getLogger(InventoryBackfillConsumer.class.getName());

    private static final String NAME = "inventory-backfill";

    private volatile Instant started;

    /**
     * The layouts this pass asks, resolved once when it starts rather than once per pointer.
     *
     * <p>That is not a micro-optimisation, it is the difference between constant work and per-object work on a
     * walk. {@code StoreRepositoryInventory.blobLayouts()} filters the declared formats through the deployment's
     * feature toggles on <em>every</em> call - around twenty formats, each with a settings lookup and its
     * required-config keys - and this consumer is handed every serving pointer in the store. Asking per delivery
     * would put that whole filter on the hot path of the one pass the cost model is built around; asking per pass
     * makes it one resolution however many pointers there are.
     *
     * <p>The set cannot meaningfully change under a pass: a format toggled off mid-walk would change what the
     * <em>walk itself</em> enumerates, so re-reading it per pointer would not be more correct either.
     */
    private volatile List<BlobLayout> layouts = List.of();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Rebuilds the inventory row of a blobs-namespace release whose row was lost, from the coordinate its "
                + "own format reads back out of a stored pointer; writes only where a row is missing.";
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.POINTERS);
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        started = pass.started();
        layouts = StoreRepositoryInventory.blobLayouts();
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        Instant recordedAt = started;
        if (recordedAt == null) {
            // Unreachable through the walk, which fires onPassStarted before a worker's first delivery. Refuse
            // rather than invent an instant: a guessed publish time is what retention ages by. Checked FIRST, not
            // beside the record below: the layouts are resolved in the same callback, so a delivery that arrived
            // early would otherwise find an empty layout set, name no coordinate, and return silently - a repair
            // that quietly did nothing, which is the one outcome worse than refusing.
            throw new IOException("the " + NAME + " consumer was handed " + artifact.path() + " before onPassStarted, "
                    + "so it has no pass instant to stamp the rebuilt row with");
        }
        ArtifactDescriptor named = null;
        BlobLayout by = null;
        for (BlobLayout layout : layouts) {
            ArtifactDescriptor answer = layout.describePointer(artifact.path()).orElse(null);
            if (answer == null) {
                continue;
            }
            if (named != null && !sameCoordinate(named, answer)) {
                // Ambiguous, so nothing is written. See the class javadoc: resolving this by order would make a
                // durable row depend on the module path, which is not something a later pass could detect.
                    LOGGER.log(System.Logger.Level.WARNING, "Two installed layouts name different coordinates for the"
                        + " pointer " + artifact.path() + " - " + by.getClass().getName() + " says "
                        + named.ecosystem() + " " + named.coordinate() + ":" + named.version() + " and "
                        + layout.getClass().getName() + " says " + answer.ecosystem() + " " + answer.coordinate()
                        + ":" + answer.version() + ". No inventory row is recorded for it; the two layouts have to"
                        + " agree, or one of them must stop claiming this key.");
                return;
            }
            named = answer;
            by = layout;
        }
        if (named == null) {
            return;                             // not a per-version pointer, or a format that cannot name one
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        MetadataDocument document = store.readVersioned(
                        MetadataKey.version(named.ecosystem(), named.coordinate(), named.version()))
                .map(versioned -> MetadataDocument.read(versioned.content()))
                .orElseGet(MetadataDocument::empty);
        if (Holdings.held(document)) {
            return;                             // already held - the idempotence a replayed delivery relies on
        }
        if (!inventory.reconciler().holdMissing(named.ecosystem(), named.coordinate(), named.version(),
                named.prerelease(), document, recordedAt)) {
            return;
        }
        // Bounded by content rather than by cadence: a row is written on the absent -> present transition only, so
        // this logs once per holding over the life of a deployment and a converged pass says nothing at all.
        LOGGER.log(System.Logger.Level.INFO, "Rebuilt the inventory row for " + named.ecosystem() + " "
                + named.coordinate() + ":" + named.version() + " at " + recordedAt + " as "
                + (Holdings.fetched(document) ? "a copy cached from an upstream" : "a release")
                + " - the retroactive sweeps can enumerate it again");
    }

    /** Whether two layouts named the same release; the ecosystem is part of it, since that is what is being found. */
    private static boolean sameCoordinate(ArtifactDescriptor left, ArtifactDescriptor right) {
        return Objects.equals(left.ecosystem(), right.ecosystem())
                && Objects.equals(left.coordinate(), right.coordinate())
                && Objects.equals(left.version(), right.version());
    }
}
