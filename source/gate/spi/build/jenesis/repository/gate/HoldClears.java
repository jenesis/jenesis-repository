package build.jenesis.repository.gate;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Withheld;

/**
 * The single guarded owner of every {@code withheld/<hash>} marker CLEAR. A blobs-namespace
 * withhold marker is content-addressed - one marker withholds the bytes wherever they serve - and clearing one is the
 * TOCTOU-dangerous verb of the withhold gate: a reader decides a marker is safe to lift, then lifts it, while a
 * concurrent enforce (or a byte-identical sibling's release) changes the world between the read and the act. Four
 * audits found the same read-then-act shape one clear-site over (#152/#153/#171/#172/#207/#214, and free #59), because
 * each new clear/rollback site had to REMEMBER to apply the proven post-clear reverify. This owner makes the reverify
 * mandatory: every clear routes through here, the withhold-egress clause fails the
 * build on a raw {@code Withheld.clear(} anywhere else, so a new release site physically cannot skip the re-verify.
 *
 * <p>The class factors the two shapes the clear-sites take, both of which are the same "clear -> reverify against fresh
 * truth -> re-mark on a racing hold" skeleton with a site-appropriate freshness predicate:
 *
 * <ul>
 *   <li><b>Reconcile ({@link #clearAndReverify}).</b> The {@code WithheldReconcileConsumer} backstop lifts a marker for
 *       which {@link #holder} answered {@link Known.Absent} - no live claimant, no {@code /quarantine} alias, no
 *       {@code holds/} record - and hands that answer on as the proof the clear seam takes.
 *       Between that judgement and the delete a concurrent enforce can write its {@code holds/} record and
 *       {@code /quarantine} pointer while its own {@link Withheld#mark} no-ops on the still-present marker (the CAS mark
 *       is a silent no-op on a present marker), so the clear strands a <em>live</em> hold with its marker gone. After
 *       each page's clears this re-runs the {@code holder} predicate against FRESH truth for exactly the lifted hashes
 *       and re-marks any hash a LIVE holder now claims. This is the logic that first lived in
 *       {@code WithheldReconcileConsumer}, now owned here and reused, not reimplemented.</li>
 *   <li><b>Release ({@link #clearReleased}).</b> The operator/auto release sites ({@code HoldLifecycle},
 *       {@code ReanalysisTask}) clear the marker of a coordinate they are releasing, UNLESS a byte-identical sibling
 *       coordinate still holds the hash ({@code HoldLifecycle#withheldByAnotherAlias}). This applies the same skeleton
 *       with the cross-alias predicate as its freshness check: guard, clear, re-check the SAME guard against fresh
 *       truth, and re-mark if a sibling was held in the window between the guard and the clear.</li>
 * </ul>
 *
 * <p>A re-mark can never strand: it fires only when the fail-safe predicate says a holder exists, and if that holder
 * later resolves the next pass lifts the marker again. A claimant EVICTED in the clear-&gt;reverify window leaves no
 * live holder, so the marker is left lifted (the clear was correct) rather than re-marked into an inert holderless
 * strand (A1-F2). Every {@link Withheld#clear} and {@link Withheld#mark} the marker-clear sites make lives in
 * this class; the enforce sweeps' own {@code Withheld.mark} (writing a fresh hold) are a different verb and stay where
 * they are - only the CLEAR is owned here.
 */
public final class HoldClears {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldClears.class);

    private HoldClears() {
    }

    /** The count of markers actually cleared this call - a marker the store really lifted, not a call that merely did
     *  not throw - and the count re-asserted by the post-clear reverify. */
    public record ClearResult(long cleared, long remarked) {}

    /**
     * One marker the reconcile has judged liftable, carrying the <em>answered</em> cross-alias question that proves it
     * so - the evidence {@link Withheld#clear} demands, produced where the judgement was made rather than re-asserted
     * at the clear.
     *
     * <p>{@code holder} is {@link Known.Determined} rather than {@link Known}, so a hash whose review queue could not
     * be enumerated cannot be put on this list at all: the mistake is a compile error where the batch is assembled,
     * not a marker lifted on a guess. In practice it is always {@link Known.Absent} - {@link #holder} answers nothing
     * else on the liftable path - but the type is what carries that from the judgement to the act.
     *
     * @param hash   the {@code withheld/<hash>} marker to lift.
     * @param holder the answered "which other live holder still claims these bytes?" that judged it liftable.
     */
    public record Orphan(String hash, Known.Determined<String> holder) {

        public Orphan {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(holder, "holder");
        }
    }

    /**
     * Clear the markers for {@code orphaned} (hashes the caller has already judged provably holderless, each carrying
     * the answered judgement that says so) and immediately reverify against fresh truth, re-marking any a live holder
     * now claims (the #207/#214 reconcile-vs-enforce fix). The {@code Withheld.clear} of one bad marker (an
     * encoding-hostile hash name) is contained per-entry so it never aborts the batch; a genuine store
     * {@link IOException} propagates so the caller retries rather than clearing on a half-read store. {@code origin} is
     * a human label for the WARN a re-mark logs. Returns how many were cleared and how many re-marked.
     *
     * <p>{@code cleared} counts markers the store actually lifted. It used to count calls that did not throw, so an
     * already-absent marker - the idempotent re-run after a crash, and every marker a racing pass had lifted first -
     * was reported to the operator (and to the {@code jenreg.withheld.markers.lifted} gauge) as work this pass did.
     * Only what was really lifted is reverified, too: re-marking a hash this call never cleared would assert a fresh
     * withhold rather than close a race it opened.
     */
    public static ClearResult clearAndReverify(ArtifactStore store, StoreRepositoryInventory inventory,
                                               List<Orphan> orphaned, String origin) throws IOException {
        if (orphaned.isEmpty()) {
            return new ClearResult(0, 0);
        }
        List<String> cleared = new ArrayList<>();
        for (Orphan orphan : orphaned) {
            try {
                // Idempotent, and fires onWithholdCleared so the derived-metadata feeds re-add the bytes. The proof
                // the caller judged with is handed straight to the seam, which lifts nothing without it.
                if (Withheld.clear(store, orphan.hash(), orphan.holder(), ArtifactDescriptor.at(null, null))) {
                    cleared.add(orphan.hash());
                }
            } catch (RuntimeException perEntry) {
                // Contain one bad marker (e.g. an encoding-hostile hash name): it never aborts the whole batch. A
                // genuine store IOException is NOT caught here - it propagates so the pass retries next interval.
                LOGGER.warn("hold-clears: skipping marker {} ({}) after a per-entry clear failure",
                        orphan.hash(), origin, perEntry);
            }
        }
        long remarked = reverify(store, inventory, cleared, origin);
        return new ClearResult(cleared.size(), remarked);
    }

    /**
     * Clear the marker for {@code hash} on a release, UNLESS a byte-identical sibling coordinate still holds it; then
     * reverify the same cross-alias guard against fresh truth and re-mark if a sibling was held in the window between
     * the guard and the clear (the #207 race, cross-alias form). {@code excludedPaths} is the releasing coordinate's own
     * served paths (the set {@code HoldLifecycle#withheldByAnotherAlias} treats as "this coordinate's own"), so this
     * coordinate's own still-present {@code /quarantine} pointer never triggers a false re-mark - only a DIFFERENT
     * coordinate that got held mid-release does. Returns {@code true} iff the marker was re-asserted.
     *
     * <p>The guard is no longer written here: the cross-alias answer <em>is</em> the argument {@link Withheld#clear}
     * takes, so "a sibling still holds it" lifts nothing by construction rather than by a caller remembering an
     * {@code if}. What must still be written here is the third state - a review queue that could not be enumerated is
     * neither a holder nor an absence, and this is a release, so it fails closed: nothing is lifted, nothing is
     * re-marked, and the reason is logged for the operator.
     */
    public static boolean clearReleased(ArtifactStore store, String hash, Set<String> excludedPaths, String origin)
            throws IOException {
        return clearReleased(store, hash, excludedPaths, origin, ArtifactDescriptor.at(null, null));
    }

    /** {@link #clearReleased}, with the transition events' subject carrying what the caller knows of the released
     *  artifact (its ecosystem, coordinate, version, path), so a stored listing finds the entry to re-admit. */
    public static boolean clearReleased(ArtifactStore store, String hash, Set<String> excludedPaths, String origin,
                                        ArtifactDescriptor subject) throws IOException {
        switch (HeldElsewhere.withheldByAnotherAlias(store, hash, excludedPaths)) {
            case Known.Unknown<String> unknown -> {
                LOGGER.warn("hold-clears: withheld/{} ({}) is left standing - the cross-alias guard could not be "
                        + "answered: {}", hash, origin, unknown.detail());
                return false;
            }
            case Known.Determined<String> holder -> {
                // A Present holder lifts nothing (the seam's own refusal); an Absent one lifts the marker. Either way
                // the answer decides at the seam, not before it.
                if (!Withheld.clear(store, hash, holder, subject)) {
                    return false;
                }
            }
        }
        // Reverify: re-run the SAME cross-alias guard against fresh truth, and only for a marker this call really
        // lifted. A sibling coordinate freshly held in the window between the guard above and the clear would
        // otherwise be un-withheld by it - re-mark to close the race (the marker is absent, so this CAS lands and
        // fires onWithheld, retracting the bytes again). An answer that could not be given re-marks too: leaving a
        // marker standing is always safe.
        boolean stillClaimed = switch (HeldElsewhere.withheldByAnotherAlias(store, hash, excludedPaths)) {
            case Known.Present<String> _ -> true;
            case Known.Unknown<String> _ -> true;
            case Known.Absent<String> _ -> false;
        };
        if (stillClaimed) {
            Withheld.mark(store, hash, subject);
            LOGGER.warn("hold-clears: re-marked withheld/{} ({}) - a byte-identical sibling was held mid-release "
                    + "(reconcile-vs-enforce race, cross-alias); the marker was re-asserted", hash, origin);
            return true;
        }
        return false;
    }

    /**
     * Close the reconcile-vs-enforce race for the markers a page just cleared:
     * re-run {@link #holder} against FRESH truth for exactly {@code lifted} and re-mark any hash a LIVE holder now
     * claims. It re-marks ONLY for a live holder - a coordinate that still resolves the hash among its current
     * {@code blobHashes} (a non-empty claimant list) AND that the fail-safe predicate does not answer holderless for.
     * A claimant EVICTED in the clear-&gt;reverify window leaves an empty claimant list, so the marker stays lifted (the
     * clear was correct) rather than re-marked into an inert holderless strand (A1-F2); if a holder later resolves, the
     * next reconcile pass lifts the marker again. Returns the number re-marked.
     */
    private static long reverify(ArtifactStore store, StoreRepositoryInventory inventory, List<String> lifted,
                                 String origin) throws IOException {
        if (lifted.isEmpty()) {
            return 0;
        }
        Map<String, List<StoreRepositoryInventory.Coordinate>> claimants =
                claimants(inventory, new HashSet<>(lifted));
        Map<String, Known<String>> aliases = HeldElsewhere.withheldByAnotherAlias(store, new HashSet<>(lifted), Set.of());
        long remarked = 0;
        for (String hash : lifted) {
            try {
                List<StoreRepositoryInventory.Coordinate> hashClaimants = claimants.getOrDefault(hash, List.of());
                Known<String> alias = aliases.getOrDefault(hash, Known.absent());
                // Re-mark ONLY for a LIVE holder (A1-F2): a coordinate that still resolves the hash among its current
                // blobHashes (a non-empty claimant list) AND that is genuinely held. If the claimant was EVICTED in the
                // clear->reverify window the claimant list is now empty, so holder() would answer Present via its
                // claimants-empty gate (c) and re-mark a marker no live coordinate serves - an inert, holderless strand.
                boolean claimed = !hashClaimants.isEmpty() && switch (holder(store, inventory, hash, hashClaimants, alias)) {
                    case Known.Present<String> _ -> true;
                    // Could not be judged at all: re-assert. This marker was standing a moment ago and this pass is
                    // what removed it, so the fail-safe direction is to put it back rather than to leave bytes served
                    // on an answer nobody could give.
                    case Known.Unknown<String> _ -> true;
                    case Known.Absent<String> _ -> false;
                };
                if (claimed) {
                    // A live holder is present: the marker is absent (just cleared), so this CAS lands and fires
                    // onWithheld - the feed retracts the bytes again, at the holder's path when one is known.
                    String holderPath = holder(store, inventory, hash, hashClaimants, alias)
                            instanceof Known.Present<String> present ? present.value() : null;
                    Withheld.mark(store, hash, ArtifactDescriptor.at(null, holderPath));
                    remarked++;
                    LOGGER.warn("hold-clears: re-marked withheld/{} ({}) - a holder appeared mid-pass "
                            + "(reconcile-vs-enforce race); the marker was re-asserted", hash, origin);
                }
            } catch (RuntimeException perEntry) {
                LOGGER.warn("hold-clears: skipping re-verify of marker {} ({}) after a per-entry failure",
                        hash, origin, perEntry);
            }
        }
        return remarked;
    }

    /**
     * Resolve, for each candidate hash, the live published coordinates that claim it - a streamed pass over the
     * published coordinate tree (O(depth) memory, never the whole set materialised), keeping only the tiny intersection
     * with the candidate set. A hash no coordinate claims maps to no entry: it is a REJECT/detached marker and the
     * {@link #holder} predicate leaves it withheld.
     */
    public static Map<String, List<StoreRepositoryInventory.Coordinate>> claimants(
            StoreRepositoryInventory inventory, Set<String> candidates) throws IOException {
        Map<String, List<StoreRepositoryInventory.Coordinate>> claimants = new HashMap<>();
        if (candidates.isEmpty()) {
            return claimants;
        }
        inventory.coordinates(coordinate -> {
            // Every hash the version claims - its blobs-namespace hashes and, for a version served from publish/
            // pointers (Maven), the hashes those pointers name - so a marker stranded on bytes a Maven release
            // serves is judged, not kept for good because no BlobLayout spoke for it.
            for (String hash : inventory.claimedHashes(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version())) {
                if (candidates.contains(hash)) {
                    claimants.computeIfAbsent(hash, key -> new ArrayList<>()).add(coordinate);
                }
            }
        });
        return claimants;
    }

    /**
     * Which other live holder still claims {@code hash}'s bytes - the reconcile backstop's whole judgement, and the
     * exact question {@link Withheld#clear} takes as its proof. {@link Known.Absent} is "none: the marker is provably
     * holderless", the only answer that lifts one; {@link Known.Present} names what keeps it standing; and
     * {@link Known.Unknown} is a leg that could not be answered at all, which is neither.
     *
     * <p>Fail-safe in every branch: a marker no live coordinate claims, one a live {@code /quarantine} pointer
     * aliases, one whose claimant serves under no nameable path, and one a claimant's {@code holds/} record still
     * covers are all kept. A store {@link IOException} out of {@code withheldByAnotherAlias} or {@code anyHolds}
     * propagates, so the caller does not clear.
     *
     * <p>It was a {@code boolean} named {@code orphaned}, and the rename is the point: {@code false} carried four
     * different facts, one of which ("nothing here could tell") is not evidence of anything, and every caller had to
     * re-derive the proof at the clear.
     */
    public static Known<String> holder(ArtifactStore store, StoreRepositoryInventory inventory, String hash,
                                       List<StoreRepositoryInventory.Coordinate> claimants) throws IOException {
        return holder(store, inventory, hash, claimants,
                claimants.isEmpty() ? Known.absent() : HeldElsewhere.withheldByAnotherAlias(store, hash, Set.of()));
    }

    /** As {@link #holder(ArtifactStore, StoreRepositoryInventory, String, List)}, with the cross-alias leg's answer
     *  for {@code hash} already in hand - the page-wide descent {@code HoldLifecycle#withheldByAnotherAlias(ArtifactStore,
     *  Set, Set)} made once for every marker of a page, so a page of M markers descends the review pointers once
     *  rather than M times. */
    public static Known<String> holder(ArtifactStore store, StoreRepositoryInventory inventory, String hash,
                                       List<StoreRepositoryInventory.Coordinate> claimants, Known<String> alias)
            throws IOException {
        // (c) No live SERVABLE coordinate claims these bytes: an OCI REJECT manifest (marker-only, never in
        //     published/) or a detached orphan we cannot prove is servable. Keep it withheld forever - fail-safe.
        if (claimants.isEmpty()) {
            return Known.known("no live published coordinate claims blobs/" + hash + " - a REJECT manifest or a "
                    + "detached marker, which stays withheld");
        }
        // (a) A live /quarantine<path> review pointer still holds the hash - this coordinate's or a byte-identical
        //     sibling's (the marker is one object for the bytes wherever served). Present and Unknown both travel
        //     back to the caller unchanged: one says who holds it, the other says nobody could look.
        if (!(alias instanceof Known.Absent<String> _)) {
            return alias;
        }
        // (b) A retroactive holds/<kind> record still covers a claimant. The enforce sweeps write the record BEFORE
        //     the marker and the /quarantine pointer, so this also catches a sibling being freshly held (the #8
        //     transient-window strand). anyHolds keys on the coordinate a served path resolves to, and answers from
        //     the durable records rather than the installed providers - so uninstalling a hold kind's module no longer
        //     makes this backstop lift the very marker that kind's hold depends on.
        for (StoreRepositoryInventory.Coordinate coordinate : claimants) {
            String claimant = coordinate.ecosystem() + " " + coordinate.coordinate() + ":" + coordinate.version();
            Known<List<String>> served = inventory.knownPaths(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version());
            if (served instanceof Known.Unknown<List<String>> unknown) {
                // Nothing installed can name the paths this claimant serves under, so the holds/ records that cover
                // it cannot be keyed at all. That is not "no hold covers it".
                return Known.unknown(unknown.cause(), "a claimant of blobs/" + hash + " (" + claimant
                        + ") cannot be judged: " + unknown.detail());
            }
            List<String> paths = served.determined().answer().orElse(List.of());
            if (paths.isEmpty()) {
                // Answered, and the answer is that it serves under no path at all - so there is still no coordinate
                // key to ask anyHolds about. Kept for the same reason it always was, now said out loud.
                return Known.known("a claimant of blobs/" + hash + " (" + claimant + ") serves under no path, so no "
                        + "holds/ record covering it can be keyed - the marker stays");
            }
            for (String path : paths) {
                if (HoldReleaseObserver.anyHolds(store, path)) {
                    return Known.known(path);
                }
            }
        }
        return Known.absent();
    }
}
