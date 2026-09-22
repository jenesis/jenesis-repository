package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The torn-write reconcile as a listener of the one walk. A dangling pointer - one whose blob is gone, impossible
 * under the blob-before-pointer ordering and so a loud sign of corruption - is found by asking the pool whether the
 * blob a pointer names is stored, one probe per pointer; it is counted, warned about, and with
 * {@code jenreg.torn-write-apply} removed through the guarded delete. The judgement used to be free: the pass handed
 * a pointer whose blob was gone over as a descriptor with a negative size, because that size was a stat of the blob.
 * Since the blob's length rides the pointer, the size is the pointer's own and says nothing about the pool, and the
 * healing suite measured the consequence on 2026-09-12 - a pointer whose blob had been deleted was never flagged,
 * every pointer reading as referenced. The probe is the reconcile's own cost, paid only where the reconcile is
 * switched on. An orphan blob - one no
 * pointer references - is judged at the end of the pass from two sets of hash prefixes, the pointers' and the
 * pool's, gathered from the pointer and blob streams in whichever order the walk delivers them; counted, never
 * removed, since reclaiming it is the collector's. The counts are on the observability report as
 * {@code jenreg.reconcile.torn.*}, summed over the last pass of every repository.
 *
 * <p>The sets are per store and per pass, eight bytes a hash, and whole only when one worker drove the whole
 * generation - a pass resumed by another worker after a crash sees a fragment, and says so by counting no orphans
 * for that generation rather than a wrong number.
 */
public final class TornWriteConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenreg.torn-write}), its scenario, its settings row. */
    public static final String NAME = "torn-write";

    /** The switch that turns flagging into removal. */
    public static final String APPLY = "torn-write-apply";

    private static final System.Logger LOGGER = System.getLogger(TornWriteConsumer.class.getName());
    private static final String BLOBS_PREFIX = "blobs/";

    /** The last pass's result per store identity, for the report. */
    private static final Map<Object, Result> LAST = new ConcurrentHashMap<>();

    private final Map<Object, Judging> judging = new ConcurrentHashMap<>();

    /** One pass's outcome over one repository. */
    public record Result(long dangling, long removed, long orphans, boolean partial) {
    }

    private static final class Judging {
        private final TornWriteReconciler.ReferencedHashes referenced = new TornWriteReconciler.ReferencedHashes();
        private final TornWriteReconciler.ReferencedHashes stored = new TornWriteReconciler.ReferencedHashes();
        private final TornWriteReconciler reconciler;
        private long dangling;
        private long removed;
        private boolean partial;

        private Judging(ArtifactStore store) {
            this.reconciler = new TornWriteReconciler(store, null);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Finds pointers whose blob never landed and blobs no pointer names, and repairs them when torn-write-apply is on; "
                + "reads each pointer the walk hands it and probes the pool once for its blob.";
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.POINTERS, Family.BLOBS);
    }

    /** A withheld pointer references its blob as much as a served one; a dangling withheld pointer is as torn. */
    @Override
    public boolean seesWithheld() {
        return true;
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        // A generation this worker did not start from its first key is a fragment for the sets below.
        judging.computeIfAbsent(store.identity(), _ -> new Judging(store)).partial = pass.done() > 0;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        judge(artifact, store);
    }

    @Override
    public void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        judge(artifact, store);
    }

    private void judge(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (!artifact.path().startsWith("/") || !TornWriteReconciler.isHash(artifact.hash())) {
            return;   // the publish/ namespace only, and only a content-addressed pointer - as the walk of its own did
        }
        Judging state = judging.computeIfAbsent(store.identity(), _ -> new Judging(store));
        if (store.exists(BLOBS_PREFIX + artifact.hash())) {
            state.referenced.add(artifact.hash());
            return;
        }
        String pointer = "publish" + artifact.path();
        boolean apply = Boolean.parseBoolean(Features.settings().apply(APPLY));
        state.dangling++;
        LOGGER.log(System.Logger.Level.WARNING, "torn-write reconcile: dangling pointer " + pointer + " -> blobs/"
                + artifact.hash() + " (blob not stored, impossible under blob-before-pointer ordering)"
                + (apply ? " - removing" : " - dry run, not removing"));
        if (apply && state.reconciler.removeDangling(pointer, artifact.hash())) {
            state.removed++;
        }
    }

    @Override
    public void onWalked(Walked entry, ArtifactStore store) {
        if (entry.family() != Family.BLOBS || !entry.key().startsWith(BLOBS_PREFIX)) {
            return;
        }
        String name = entry.key().substring(BLOBS_PREFIX.length());
        if (TornWriteReconciler.isHash(name)) {
            judging.computeIfAbsent(store.identity(), _ -> new Judging(store)).stored.add(name);
        }
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        Judging state = judging.remove(store.identity());
        if (state == null) {
            LAST.put(store.identity(), new Result(0, 0, 0, false));   // nothing delivered: nothing torn, nothing stored
            return;
        }
        long orphans = 0;
        if (!state.partial) {
            state.referenced.seal();
            state.stored.seal();
            orphans = state.stored.countNotIn(state.referenced);
        }
        LAST.put(store.identity(), new Result(state.dangling, state.removed, orphans, state.partial));
    }

    /** The last pass's results, by store identity - what the report sums. */
    public static Map<Object, Result> last() {
        return Map.copyOf(LAST);
    }

    /** The counts on the observability report: dangling pointers found, removed, and orphan blobs confirmed, summed
     *  over the last pass of every repository this node walked. */
    public static final class Observability implements ObservabilitySource {

        public Observability() {
        }

        @Override
        public List<Metric> metrics() {
            long dangling = 0;
            long removed = 0;
            long orphans = 0;
            for (Result result : LAST.values()) {
                dangling += result.dangling();
                removed += result.removed();
                orphans += result.orphans();
            }
            return List.of(
                    Metric.gauge("jenreg.reconcile.torn.dangling", "Pointers resolving to a missing blob found by the "
                            + "last walk of each repository (impossible under blob-before-pointer ordering, so a loud "
                            + "signal of corruption).", dangling, "pointers"),
                    Metric.gauge("jenreg.reconcile.torn.removed", "Dangling pointers removed by the last walk of each "
                            + "repository (zero on a dry run; removal needs jenreg.torn-write-apply).", removed,
                            "pointers"),
                    Metric.gauge("jenreg.reconcile.torn.orphans", "Orphan blobs confirmed by the last walk of each "
                            + "repository - stored, referenced by no pointer, left to the garbage collector.", orphans,
                            "blobs"));
        }
    }
}
