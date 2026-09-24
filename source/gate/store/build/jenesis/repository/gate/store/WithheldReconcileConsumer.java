package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.gate.HoldClears;
import build.jenesis.repository.gate.HeldElsewhere;

/**
 * The withhold-marker reconcile as a listener of the one walk: the backstop that lifts a content-addressed
 * {@code withheld/<hash>} marker no live holder remains for - a marker stranded by two byte-identical aliases
 * releasing at once, by a crash in the enforce sweep's marker-before-pointer window, or a pre-existing orphan. The
 * judgement is the one {@link HoldClears#holder} makes, and its three inputs are gathered from the walk instead of
 * from descents of the reconcile's own: the markers are listed once per pass, bounded by how many there are; the
 * claimants of each marked hash come from the inventory rows as the pass hands them over; and the live review
 * pointers under {@code /quarantine} - withheld pointers, which this consumer asks to see - answer the cross-alias
 * question one pointer at a time. At the end of the pass every marker is judged, the holderless ones cleared and
 * re-verified against fresh truth exactly as before. Fail-safe in every branch it always was: a marker nobody can
 * judge stands.
 */
public final class WithheldReconcileConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenreg.withheld-reconcile}), its scenario, its settings row. */
    public static final String NAME = "withheld-reconcile";

    private static final Logger LOGGER = LoggerFactory.getLogger(WithheldReconcileConsumer.class);
    private static final int PAGE = 1000;
    /** The marker namespace, listed once per pass: entries uncapped (a pass that stopped early would leave an
     *  artifact withheld with nothing holding it), the round-trip budget the binding bound. */
    private static final BoundedChildren MARKERS =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).steps(100_000).page(PAGE);
    private static final Map<Object, Result> LAST = new ConcurrentHashMap<>();

    private final Map<Object, Judging> judging = new ConcurrentHashMap<>();

    /** One pass's outcome over one repository: markers lifted, and re-asserted because a hold went live mid-pass. */
    public record Result(long lifted, long remarked) {
    }

    private static final class Judging {
        private final ArtifactStore store;
        private final StoreRepositoryInventory inventory;
        private final Set<String> markers = new HashSet<>();
        private final Map<String, List<StoreRepositoryInventory.Coordinate>> claimants = new HashMap<>();
        private final Map<String, Known<String>> aliases = new HashMap<>();

        private Judging(ArtifactStore store) throws IOException {
            this.store = store;
            this.inventory = new StoreRepositoryInventory(store);
            MARKERS.scan(store, "withheld", markers::add);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Lifts a withhold marker nothing claims any more and re-marks a hold whose marker was lost, re-verifying each "
                + "through the gate; reads each pointer and row the walk hands it and lists the markers once.";
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.POINTERS, Family.INVENTORY);
    }

    /** The live review pointers under {@code /quarantine} are withheld pointers: they are the cross-alias leg. */
    @Override
    public boolean seesWithheld() {
        return true;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        // A served pointer holds nothing withheld; the claimants come from the inventory rows below.
    }

    @Override
    public void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (!artifact.path().startsWith("/quarantine/")) {
            return;   // withheld by marker or chain, not a review pointer - no alias to answer from it
        }
        Judging state = judging(store);
        if (state.markers.isEmpty()) {
            return;
        }
        String servedPath = artifact.path().substring("/quarantine".length());
        // A review pointer naming a marked hash holds it; otherwise the sibling coordinate the pointer belongs to
        // resolves its hash set once, and every marked hash in it is held; a pointer nobody can judge leaves every
        // still-open marker Unknown, since it could be holding any of them.
        if (state.markers.contains(artifact.hash())) {
            state.aliases.put(artifact.hash(), Known.known("a live /quarantine review pointer holds blobs/"
                    + artifact.hash() + ": " + artifact.path()));
            return;
        }
        Known<Set<String>> held;
        try {
            held = HeldElsewhere.siblingHashes(state.inventory, servedPath);
        } catch (RuntimeException hostile) {
            held = Known.failed("the review pointer " + artifact.path() + " could not be judged", hostile);
        }
        switch (held) {
            case Known.Present<Set<String>> present -> {
                for (String hash : present.value()) {
                    if (state.markers.contains(hash)) {
                        state.aliases.put(hash, Known.known("a byte-identical sibling under review holds blobs/"
                                + hash + ": " + artifact.path()));
                    }
                }
            }
            case Known.Unknown<Set<String>> unknown -> {
                for (String hash : state.markers) {
                    state.aliases.putIfAbsent(hash, Known.unknown(unknown.cause(), unknown.detail()));
                }
            }
            case Known.Absent<Set<String>> _ -> {
                // a placed path naming no versioned artifact holds nothing
            }
        }
    }

    @Override
    public void onWalked(Walked entry, ArtifactStore store) throws IOException {
        if (entry.family() != Family.INVENTORY) {
            return;
        }
        Judging state = judging(store);
        if (state.markers.isEmpty()) {
            return;
        }
        String root = state.inventory.publishedRoot();
        if (!entry.key().startsWith(root + "/")) {
            return;
        }
        String[] segments = entry.key().substring(root.length() + 1).split("/");
        if (segments.length != 3 || segments[2].startsWith("@")) {
            return;   // not a version release row
        }
        StoreRepositoryInventory.Coordinate coordinate = new StoreRepositoryInventory.Coordinate(segments[0],
                StoreRepositoryInventory.decode(segments[1]), segments[2]);
        // Every hash the version claims - its blobs-namespace hashes and, for a version served from publish/
        // pointers, the hashes those pointers name - so a marker stranded on bytes a Maven release serves is judged.
        for (String hash : state.inventory.claimedHashes(coordinate.ecosystem(), coordinate.coordinate(),
                coordinate.version())) {
            if (state.markers.contains(hash)) {
                state.claimants.computeIfAbsent(hash, _ -> new ArrayList<>()).add(coordinate);
            }
        }
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        Judging state = judging.remove(store.identity());
        if (state == null) {
            return;   // nothing was delivered for this store, so no marker was listed and none is judged
        }
        try {
            LAST.put(store.identity(), judge(state));
        } catch (IOException unjudgeable) {
            throw new UncheckedIOException(unjudgeable);
        }
    }

    /** Judge every marker against what the pass gathered; clear the holderless ones and re-verify each. */
    private static Result judge(Judging state) throws IOException {
        List<HoldClears.Orphan> orphaned = new ArrayList<>();
        for (String hash : state.markers) {
            try {
                switch (HoldClears.holder(state.store, state.inventory, hash,
                        state.claimants.getOrDefault(hash, List.of()), state.aliases.getOrDefault(hash, Known.absent()))) {
                    case Known.Absent<String> holderless -> orphaned.add(new HoldClears.Orphan(hash, holderless));
                    case Known.Present<String> _ -> { }        // a live holder still needs it - the marker stays
                    case Known.Unknown<String> unknown -> LOGGER.warn(
                            "withheld-reconcile: leaving withheld/{} standing - it could not be judged: {}",
                            hash, unknown.detail());
                }
            } catch (RuntimeException perEntry) {
                // Contain one bad marker (an encoding-hostile hash name): it never aborts the judgement of the rest.
                // A genuine store IOException is NOT caught here - it fails this consumer's generation, which the
                // pass records, so nothing is cleared against a half-read store (fail-safe).
                LOGGER.warn("withheld-reconcile: skipping marker {} after a per-entry failure", hash, perEntry);
            }
        }
        HoldClears.ClearResult result = HoldClears.clearAndReverify(state.store, state.inventory, orphaned,
                "withheld-reconcile");
        if (result.cleared() > 0) {
            LOGGER.warn("withheld-reconcile lifted {} holderless withhold marker(s)", result.cleared());
        }
        return new Result(result.cleared(), result.remarked());
    }

    private Judging judging(ArtifactStore store) throws IOException {
        Judging state = judging.get(store.identity());
        if (state == null) {
            state = new Judging(store);
            judging.put(store.identity(), state);
        }
        return state;
    }

    /** The last pass's results, by store identity - what the report sums and a test reads. */
    public static Map<Object, Result> last() {
        return Map.copyOf(LAST);
    }

    /** The counts on the observability report, summed over the last pass of every repository this node walked. */
    public static final class Observability implements ObservabilitySource {

        public Observability() {
        }

        @Override
        public List<Metric> metrics() {
            long lifted = 0;
            long remarked = 0;
            for (Result result : LAST.values()) {
                lifted += result.lifted();
                remarked += result.remarked();
            }
            return List.of(
                    Metric.gauge("jenreg.withheld.markers.lifted", "Holderless withheld/<hash> markers lifted by the "
                            + "last walk of each repository.", lifted, "markers"),
                    Metric.gauge("jenreg.withheld.markers.remarked", "Re-asserted withheld markers whose hold went "
                            + "live mid-pass (the reconcile-versus-enforce race) in the last walk of each repository.",
                            remarked, "markers"));
        }
    }
}
