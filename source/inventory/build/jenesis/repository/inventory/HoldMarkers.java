package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.Traversal;

/**
 * The single owner of the durable {@code holds/<kind>/<eco>/<coord>/<ver>} key space, read <em>by coordinate</em> -
 * the half of the hold question that needs no installed format at all.
 *
 * <p><b>Why this half lives here.</b> made the record, not the provider list, the answer to "is this held", and
 * put that owner in the gate ({@code HoldReleaseObserver}'s home) because the gate is what writes and releases holds.
 * But the coordinate-keyed read has a second consumer one module down: the inventory's name-enumeration screen
 * ({@code InventoryBrowse.disclosable}) has to know whether a version is held for an ecosystem <em>no installed format
 * can place</em>, which is exactly when neither of its own withholding faces - both layout-resolved - can be asked
 *. The gate depends on the inventory, not the other way round, so the read had to come down; and it comes down
 * to the module that already owns every sibling per-version key space ({@code published/}, {@code downloaded/},
 * {@code pinned/}, {@code licenses/}, and - since the earlier work, for the same reason - the {@code overrides/} twin in
 * {@link OverrideRecords}). {@code HoldRecords} in the gate keeps its whole public surface and delegates its
 * coordinate-keyed reads here, so there is still exactly one construction of these keys and exactly one enumeration of
 * the kind index. What stays in the gate is everything that needs discovery: the path-keyed forms (which need a format
 * to turn a request path into a coordinate), the installed-provider join, and the operator's orphan reap.
 *
 * <p><b>Fail-closed.</b> Every read here propagates its {@link IOException} rather than answering "not held": a caller
 * that cannot prove no hold covers a version must not disclose or clear it. The probes go through
 * {@link ArtifactStore#readVersioned} rather than {@link ArtifactStore#exists} for exactly that reason - {@code exists}
 * has no way to report a store failure, so an I/O error would read as "not held". A truncated kind enumeration is
 * refused rather than returned, because a short kind list reads as "that kind is not holding".
 */
public final class HoldMarkers {

    /** The {@code holds/} root: the second segment of every key under it is a hold kind and nothing else, which is
     *  what makes {@link #kinds} an index rather than a guess. Public because this class is the space's single
     *  composer and two things outside it name the space by reference rather than by re-spelling it: the
     *  gate's manifest entry ({@code GateStorageNamespace}) and the dispatch squat below. */
    public static final String ROOT = "holds";

    /** The one segment under {@link #ROOT} that is NOT a hold kind: the gate's stored quarantine replay context keys
     *  itself at {@code holds/dispatch<path>}. It shares the root because it is hold-scoped state that lives and dies
     *  with the hold, so the collision is declared here - once, where the enumeration happens - rather than discovered
     *  by a reader of {@code holds/} that mistakes it for a kind. The gate builds its own root from this constant (via
     *  {@code HoldRecords.DISPATCH}), so the two can never drift apart. */
    public static final String DISPATCH = "dispatch";

    /** The squatter's whole root, {@code holds/dispatch}, composed here so the gate's {@code QuarantineDispatch}
     *  builds it from this constant instead of re-spelling {@code "holds/"} beside it. Before only the second
     *  segment was shared, so renaming {@link #ROOT} would have left the squat writing under the old root while
     *  {@link #kinds} enumerated the new one - and the exclusion that keeps {@code dispatch} from reading as a hold
     *  kind would have stopped excluding anything. */
    public static final String DISPATCH_ROOT = ROOT + "/" + DISPATCH;

    private HoldMarkers() {
    }

    /** The durable record key for one kind's hold on one coordinate version. Every coordinate segment is URL-encoded:
     *  an un-encoded version or ecosystem carrying a {@code /} (or empty) would splice extra segments into the key and
     *  let one release's record collide with another's - and a kind-neutral reader could not construct it. The kind
     *  segment is not encoded, exactly as in {@link OverrideRecords#key}: a kind token is a fixed identifier of the
     *  module that owns it, not user input. */
    public static String key(String kind, String ecosystem, String coordinate, String version) {
        return ROOT + "/" + kind + "/" + encode(ecosystem) + "/" + encode(coordinate) + "/" + encode(version);
    }

    /** The kind index's bounds. It is one level of {@code holds/} - a segment per hold kind that has ever written a
     *  record, plus {@link #DISPATCH} - so no upload can grow it and the caps are never near. They are still declared
     *  and still refused loudly (below) rather than left to the raw level listing: a SHORT answer here reads as "that
     *  kind is not holding", which is the exact fail-open shape this whole space exists to remove. */
    private static final BoundedChildren KINDS = BoundedChildren.bounded();

    /** Every hold kind that has ever written a record in this scoped store - the second segment of {@code holds/},
     *  minus the {@link #DISPATCH} squatter. Note what this is NOT: it is not the installed providers, and a kind
     *  appears here for as long as one of its records survives, whether or not its module is still on the graph.
     *
     *  <p>A truncated enumeration is refused rather than returned: dropping a kind from this index would silently
     *  un-hold every coordinate that kind holds, so "I could not enumerate the kinds" and "nothing holds this" must
     *  never be the same answer. A round-trip-budget exhaustion raises the primitive's own named
     *  {@code TraversalException} for the same reason. */
    public static SortedSet<String> kinds(ArtifactStore store) throws IOException {
        SortedSet<String> kinds = new TreeSet<>();
        Traversal.Result result = KINDS.scan(store, ROOT, name -> {
            if (!DISPATCH.equals(name)) {
                kinds.add(name);
            }
        });
        if (result.truncated()) {
            throw new IOException("the holds/ kind index did not enumerate whole (" + result.delivered()
                    + " delivered); refusing to answer, because a short kind list reads as 'not held'");
        }
        return kinds;
    }

    /** The kinds holding {@code (ecosystem, coordinate, version)} right now, read from the durable records alone -
     *  no format, no provider list, no discovery of any sort. */
    public static SortedSet<String> heldKinds(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        SortedSet<String> held = new TreeSet<>();
        for (String kind : kinds(store)) {
            // readVersioned, not exists: a store failure must propagate, or an unreadable record would read as "not
            // held" and a caller would clear (or disclose) a hold it could not prove was gone.
            if (store.readVersioned(key(kind, ecosystem, coordinate, version)).isPresent()) {
                held.add(kind);
            }
        }
        return held;
    }

    /** Whether ANY kind holds a durable record for the coordinate version - {@link #heldKinds} for a caller that only
     *  needs the yes/no, short-circuiting on the first hit so the common unheld case costs one bounded level listing
     *  and one point read per kind, and the held case rather less. */
    public static boolean anyHeld(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        for (String kind : kinds(store)) {
            if (store.readVersioned(key(kind, ecosystem, coordinate, version)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
