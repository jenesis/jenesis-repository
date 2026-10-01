package build.jenesis.repository.gate;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Withheld;

/**
 * The guarded clear of {@code withheld/<hash>} markers for the release and reconcile sites. A blobs-namespace marker is
 * content-addressed, so clearing one is a read-then-act race: a concurrent enforce, or a byte-identical sibling's
 * hold, can change the answer between the judgement and the delete. Both shapes here clear, re-verify against fresh
 * state, and re-mark when a holder appeared in the window:
 *
 * <ul>
 *   <li><b>Reconcile ({@link #clearAndReverify}).</b> The {@code WithheldReconcileConsumer} lifts markers {@link #holder}
 *       judged {@link Known.Absent}. A concurrent enforce can write its {@code holds/} record and {@code /quarantine}
 *       pointer while its own {@link Withheld#mark} no-ops on the still-present marker, so after each page this re-runs
 *       {@link #holder} for exactly the lifted hashes and re-marks any a live holder now claims.</li>
 *   <li><b>Release ({@link #clearReleased}).</b> {@code HoldLifecycle} and {@code ReanalysisTask} clear a releasing
 *       coordinate's marker unless a byte-identical sibling still holds the hash, then re-check that guard and re-mark
 *       if a sibling was held in the window.</li>
 * </ul>
 *
 * <p>A re-mark fires only for a live holder, so it never strands: a claimant evicted in the window leaves the marker
 * lifted, and a holder that later resolves has the marker lifted again by the next pass. The enforce sweeps' own
 * {@code Withheld.mark} of a fresh hold is a different verb and stays with them.
 */
public final class HoldClears {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldClears.class);

    private HoldClears() {
    }

    /** How many markers the store really lifted this call, and how many the reverify re-asserted. */
    public record ClearResult(long cleared, long remarked) {}

    /**
     * One marker the reconcile judged liftable, carrying the answered holder question {@link Withheld#clear} takes as
     * its proof. {@code holder} is {@link Known.Determined}, so a hash whose review queue could not be enumerated cannot
     * be put on the list at all.
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
     * Clears the markers for {@code orphaned} and reverifies against fresh state, re-marking any a live holder now
     * claims. A runtime failure on one marker is contained per entry; a store {@link IOException} propagates so the
     * caller retries. {@code origin} labels the warning a re-mark logs.
     *
     * <p>Only markers the store really lifted are counted and reverified, so an already-absent marker is neither
     * reported as this pass's work nor re-marked as a fresh withhold.
     */
    public static ClearResult clearAndReverify(ArtifactStore store, StoreRepositoryInventory inventory,
                                               List<Orphan> orphaned, String origin) throws IOException {
        if (orphaned.isEmpty()) {
            return new ClearResult(0, 0);
        }
        List<String> cleared = new ArrayList<>();
        for (Orphan orphan : orphaned) {
            try {
                // Fires onWithholdCleared, so the derived-metadata feeds re-add the bytes.
                if (Withheld.clear(store, orphan.hash(), orphan.holder(), ArtifactDescriptor.at(null, null))) {
                    cleared.add(orphan.hash());
                }
            } catch (RuntimeException perEntry) {
                // One bad marker (an encoding-hostile hash name) never aborts the batch.
                LOGGER.warn("hold-clears: skipping marker {} ({}) after a per-entry clear failure",
                        orphan.hash(), origin, perEntry);
            }
        }
        long remarked = reverify(store, inventory, cleared, origin);
        return new ClearResult(cleared.size(), remarked);
    }

    /**
     * Clears the marker for {@code hash} on a release unless a byte-identical sibling still holds it, then re-checks
     * that guard and re-marks if a sibling was held in the window. {@code excludedPaths} are the releasing coordinate's
     * own served paths, so its own {@code /quarantine} pointer never causes a re-mark. A review queue that could not be
     * enumerated fails closed: nothing is lifted and the reason is logged. Returns {@code true} iff the marker was
     * re-asserted.
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
                // The seam lifts nothing for a Present holder.
                if (!Withheld.clear(store, hash, holder, subject)) {
                    return false;
                }
            }
        }
        // Reverify for a marker this call lifted: a sibling held in the window is re-marked, which fires onWithheld and
        // retracts the bytes again. An unanswerable guard re-marks too.
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
     * Re-runs {@link #holder} for exactly {@code lifted} and re-marks any hash a live holder now claims: a non-empty
     * claimant list that {@link #holder} does not answer holderless for. Returns the number re-marked.
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
                // An empty claimant list is checked here because holder() answers Present for it (gate c), which
                // would re-mark a marker no live coordinate serves.
                boolean claimed = !hashClaimants.isEmpty() && switch (holder(store, inventory, hash, hashClaimants, alias)) {
                    case Known.Present<String> _ -> true;
                    // This pass removed the marker, so an unanswerable judgement puts it back.
                    case Known.Unknown<String> _ -> true;
                    case Known.Absent<String> _ -> false;
                };
                if (claimed) {
                    // Fires onWithheld, retracting the bytes at the holder's path when one is known.
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
     * The live published coordinates claiming each candidate hash, from a streamed pass over the coordinate tree that
     * keeps only the intersection with the candidates. A hash no coordinate claims has no entry.
     */
    public static Map<String, List<StoreRepositoryInventory.Coordinate>> claimants(
            StoreRepositoryInventory inventory, Set<String> candidates) throws IOException {
        Map<String, List<StoreRepositoryInventory.Coordinate>> claimants = new HashMap<>();
        if (candidates.isEmpty()) {
            return claimants;
        }
        inventory.coordinates(coordinate -> {
            // Blobs-namespace hashes and the hashes publish/ pointers name, so a marker on bytes a Maven release serves
            // is judged too.
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
     * Which live holder still claims {@code hash}'s bytes: the question {@link Withheld#clear} takes as its proof.
     * {@link Known.Absent} (provably holderless) is the only answer that lifts a marker, {@link Known.Present} names the
     * holder, and {@link Known.Unknown} is a leg that could not be answered.
     *
     * <p>Fail-safe in every branch: a marker no live coordinate claims, one a live {@code /quarantine} pointer aliases,
     * one whose claimant serves under no nameable path and one a {@code holds/} record covers are all kept. A store
     * {@link IOException} propagates, so the caller does not clear.
     */
    public static Known<String> holder(ArtifactStore store, StoreRepositoryInventory inventory, String hash,
                                       List<StoreRepositoryInventory.Coordinate> claimants) throws IOException {
        return holder(store, inventory, hash, claimants,
                claimants.isEmpty() ? Known.absent() : HeldElsewhere.withheldByAnotherAlias(store, hash, Set.of()));
    }

    /** As {@link #holder(ArtifactStore, StoreRepositoryInventory, String, List)}, with the cross-alias answer already
     *  in hand, so a page of markers descends the review pointers once rather than once per marker. */
    public static Known<String> holder(ArtifactStore store, StoreRepositoryInventory inventory, String hash,
                                       List<StoreRepositoryInventory.Coordinate> claimants, Known<String> alias)
            throws IOException {
        // (c) No live coordinate claims these bytes - an OCI REJECT manifest or a detached marker - so it stays.
        if (claimants.isEmpty()) {
            return Known.known("no live published coordinate claims blobs/" + hash + " - a REJECT manifest or a "
                    + "detached marker, which stays withheld");
        }
        // (a) A live /quarantine<path> pointer, this coordinate's or a byte-identical sibling's, holds the hash.
        if (!(alias instanceof Known.Absent<String> _)) {
            return alias;
        }
        // (b) A holds/<kind> record covers a claimant. The enforce sweeps write it before the marker and the pointer,
        //     so this also catches a sibling being held right now; anyHolds answers from the durable records, so an
        //     uninstalled hold kind still keeps its marker.
        for (StoreRepositoryInventory.Coordinate coordinate : claimants) {
            String claimant = coordinate.ecosystem() + " " + coordinate.coordinate() + ":" + coordinate.version();
            Known<List<String>> served = inventory.knownPaths(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version());
            if (served instanceof Known.Unknown<List<String>> unknown) {
                // The paths cannot be named, so no holds/ record can be keyed; that is not "no hold covers it".
                return Known.unknown(unknown.cause(), "a claimant of blobs/" + hash + " (" + claimant
                        + ") cannot be judged: " + unknown.detail());
            }
            List<String> paths = served.determined().answer().orElse(List.of());
            if (paths.isEmpty()) {
                // Serves under no path, so there is no key to ask anyHolds about.
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
