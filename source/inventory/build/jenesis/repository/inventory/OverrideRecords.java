package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The single owner of the durable {@code overrides/<kind>/<eco>/<coord>/<ver>} key space - the marker a human's
 * release of a hold writes, saying "this kind's finding on this stored version has been cleared, do not re-hold it".
 *
 * <p><b>Why one owner.</b> The space had four independent spellings of the same key. {@code KevHold} and
 * {@code LicenseHold} URL-encoded only the coordinate ({@code overrides/kev/<raw eco>/<enc coord>/<raw ver>});
 * {@code ReachabilityHold} encoded all three segments; {@code InventoryEviction} reaped by the first spelling and
 * {@code InventoryReconciler} parsed it back the same way. So evicting a version <em>stranded</em> its reachability
 * override: the reaper composed a key the writer never wrote, deleted nothing, and left a marker behind that outlived
 * the version it was about - and would silently suppress the re-screen of a later re-publish of the same coordinate.
 * The same divergence in {@code holds/} was what made a hold un-findable by a kind-neutral reader until 
 * converged that space onto {@code HoldRecords.key}; this class is that convergence for {@code overrides/}, and the
 * reason there is now nowhere left for a fifth spelling to appear.
 *
 * <p><b>The spelling is the fully-encoded one</b>, for the reason {@code HoldRecords} already states: an un-encoded
 * version or ecosystem carrying a {@code /} (or empty) splices extra segments into the key, so one release's marker
 * can land on another's - and a kind-neutral reader (this space has two: the eviction reaper and the reconcile sweep)
 * cannot construct a key it cannot predict. Override records are transient by construction - they exist between a
 * human's release and the eviction of the version they are about - so the previous spellings are simply gone rather
 * than read as a fallback; a pre-existing marker written the old way is left where it lies and reaped, like any other
 * unowned row, by the operator purge of the {@code overrides} namespace.
 *
 * <p><b>What lives here and what does not.</b> This class owns the <em>key</em> - construction, the kind index, and
 * the parse back - and nothing else. An override's <em>body</em> stays the private vocabulary of the kind that wrote
 * it (a CVE set, a licence reason), which is why there is no read or write of a body here and why nothing ever
 * promotes an override on another kind's behalf. The kind segment is not encoded, exactly as in
 * {@code HoldRecords.key}: a kind token is a fixed identifier of the module that owns it, not user input.
 *
 * <p>It sits in the inventory module rather than beside {@code HoldRecords} in the gate because the inventory is what
 * <em>reaps</em> this space - {@code InventoryEviction} on a version's destruction, {@code InventoryReconciler} on
 * the derived-row sweep - and the gate already depends on the inventory, not the other way round. The inventory also
 * already owns every sibling per-version key space ({@code published/}, {@code downloaded/}, {@code pinned/},
 * {@code licenses/}), so this is the space joining them rather than a new home being invented for it.
 */
public final class OverrideRecords {

    /** The {@code overrides/} root: the second segment of every key under it is a hold kind and nothing else.
     *  Public because this class is the space's single composer: the gate's manifest entry declares this
     *  constant rather than re-spelling the literal, so a rename cannot leave the declaration behind. */
    public static final String ROOT = "overrides";

    private OverrideRecords() {
    }

    /** The durable marker key for one kind's cleared hold on one coordinate version. Every coordinate segment is
     *  URL-encoded, so the writer and the two kind-neutral reapers compose the identical key and no segment carrying a
     *  {@code /} can splice one release's marker onto another's. */
    public static String key(String kind, String ecosystem, String coordinate, String version) {
        return ROOT + "/" + kind + "/" + encode(ecosystem) + "/" + encode(coordinate) + "/" + encode(version);
    }

    /** Every hold kind that has ever written an override marker in this scoped store - one level of {@code overrides/},
     *  so a kind appears for as long as one of its markers survives, whether or not its module is still installed. The
     *  reaper enumerates this rather than a hard-coded kind list precisely so that uninstalling a module does not
     *  strand its markers: they are still this space's rows and a version's eviction still takes them with it. */
    public static SortedSet<String> kinds(ArtifactStore store) throws IOException {
        return new TreeSet<>(store.list(ROOT));
    }

    /** The kind and coordinate version an {@code overrides/} key names, or empty for a key that is not one of this
     *  space's four-segment rows (a stray object, or a level above the leaf). The inverse of {@link #key}, here rather
     *  than in each sweep, so a reader can never decode a spelling the writer does not produce. */
    public static Optional<Row> parse(String key) {
        String[] parts = key.split("/");
        if (parts.length != 5 || !ROOT.equals(parts[0])) {
            return Optional.empty();
        }
        return Optional.of(new Row(parts[1], decode(parts[2]), decode(parts[3]), decode(parts[4])));
    }

    /** One parsed {@code overrides/} row: which kind cleared which coordinate version. */
    public record Row(String kind, String ecosystem, String coordinate, String version) {
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
