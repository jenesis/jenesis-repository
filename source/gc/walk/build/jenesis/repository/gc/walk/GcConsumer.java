package build.jenesis.repository.gc.walk;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * Runs the collector at the end of a walk, reclaiming the storage of content nothing points at.
 *
 * <p>It rides the walk rather than scheduling itself, so a deployment pays for one enumeration rather than two, and
 * it runs only over a store whose pass it saw. With no collector installed or selected it does nothing and the
 * capability surfaces say so - absence never deletes.
 */
public final class GcConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenrepo.collect}) and how a walk entry names it. */
    public static final String NAME = "collect";

    private static final Logger LOGGER = LoggerFactory.getLogger(GcConsumer.class);

    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

    /** The contributed roots, resolved on the first pass rather than in the constructor: this class is itself
     *  instantiated by a service load, and asking for a second service from inside that one resolves nothing. */
    private volatile GcRoots roots;

    public GcConsumer() {
    }

    public GcConsumer(GcRoots roots) {
        this.roots = roots;
    }

    private GcRoots roots() {
        GcRoots resolved = roots;
        if (resolved == null) {
            resolved = GcRoots.installed();
            roots = resolved;
        }
        return resolved;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * None: the mark reads the pointers itself over its own pass, so a member handed to it here would be read twice.
     * An entry naming only this consumer therefore walks nothing - the driving pass costs a manifest and a generation
     * - and on a shared entry the other consumers' families give the pass its roots and this one takes no delivery.
     */
    @Override
    public Set<WalkConsumer.Family> families() {
        return Set.of();
    }

    /** Redundant while {@link #families()} is empty, and declared so that a family added later does not start paying
     *  for pointer bodies the collector never reads. */
    @Override
    public boolean needsPointers() {
        return false;
    }

    /** A held artifact's blob is referenced as much as a served one's, which is why the mark reads the pointers
     *  itself rather than the walk's screened view, so the distinction would cost two reads per pointer for nothing. */
    @Override
    public boolean needsWithheldStatus() {
        return false;
    }

    /** Last, so it reclaims what the other consumers' completion work orphaned in this same pass rather than in
     *  the next one - a retention sweep unpublishes during its own completion. */
    @Override
    public int order() {
        return LAST;
    }

    @Override
    public String description() {
        return "Runs the garbage collector at the end of the walk: marks every blob a served or held pointer names and "
                + "sweeps the rest, over its own pass of the pointers and the pool; reads every pointer and lists "
                + "every blob.";
    }

    @Override
    public List<String> settings() {
        return List.of();
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        riding.add(store.identity());
    }

    /** The collector for this pass, resolved from the settings as they are now: what it reclaims is recorded for
     *  the node rather than on the instance, so a fresh one per pass loses nothing and follows a changed dial. */
    private static Optional<GarbageCollector> collector() {
        return GarbageCollectorProvider.resolve(Features.settings());
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        if (!riding.remove(store.identity())) {
            return;
        }
        Optional<GarbageCollector> collector = collector();
        if (collector.isEmpty()) {
            return;   // no collector installed or configured: nothing is reclaimed, and the capabilities say so
        }
        try {
            GcPlan plan = collector.get().collect(store, roots().roots(store), Instant.now());
            plan.refusal().ifPresent(refusal -> LOGGER.warn("gc: {}", refusal.detail()));
        } catch (IOException uncollected) {
            throw new UncheckedIOException(uncollected);
        }
    }
}
