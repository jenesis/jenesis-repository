package build.jenesis.repository.walk.task;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * One scheduled walk: per repository, join the pass named after this task ({@code walks/<name>}) over every key family
 * its consumers listen on - the pointer roots ({@code publish/} plus every installed blobs-namespace format's roots,
 * the {@link StoreRepositoryInventory#pointerRoots() union} the collector judges references from), the inventory rows,
 * the blob pool, the derived rows - and hand each member to the consumers riding it: N rebuilders, one enumeration.
 * {@link RebuildPass} owns the delivery contract (descriptor richness, once per pass, a failing consumer failing alone
 * and redelivered next generation); this task schedules it and reports the pass's failure records. The {@code rebuild}
 * task carries every consumer and is what a standing request runs; every other task is one entry of the walks setting,
 * on its cron, carrying the consumers it names. A pass another node still holds segments of returns incomplete and
 * resumes on the next run.
 */
public final class RebuildTask implements MaintenanceTask {

    /** The task every consumer rides, and a request runs: its name, its lease and its pass scope. */
    public static final String REBUILD = "rebuild";

    /** A schedule-less task's interval and next moment: far enough that the clock never runs it, near enough to fit a
     *  duration - a request runs it. */
    private static final Duration NEVER = Duration.ofDays(3650);

    private final String name;
    private final WalkSchedules.Entry schedule;
    private final ArtifactWalk walk;
    private final List<WalkConsumer> consumers;

    /** The walk {@code name}, on {@code schedule} ({@code null} for one only a request runs), over
     *  {@code consumers}. */
    public RebuildTask(String name, WalkSchedules.Entry schedule, ArtifactWalk walk, List<WalkConsumer> consumers) {
        this.name = name;
        this.schedule = schedule;
        this.walk = walk;
        this.consumers = List.copyOf(consumers);
    }

    @Override
    public String name() {
        return name;
    }

    /** The consumers this walk carries, by name - what the walks screen shows beside each entry. */
    public List<String> consumers() {
        List<String> names = new ArrayList<>();
        for (WalkConsumer consumer : consumers) {
            names.add(consumer.name());
        }
        return names;
    }

    /** The nominal period of the schedule: the gap between its next two moments; {@link #NEVER} without one. */
    @Override
    public Duration interval() {
        if (schedule == null) {
            return NEVER;
        }
        Instant first = next(Instant.now());
        return Duration.between(first, next(first));
    }

    @Override
    public Instant next(Instant after) {
        if (schedule == null) {
            return after.plus(NEVER);
        }
        return schedule.next(after).orElse(after.plus(NEVER));
    }

    /** Exclusive as well as walk-claimed. Segment claims keep {@code PER_ITEM_DURABLE} and {@code STRIDE_DURABLE}
     *  consumers correct under fan-out, but the pass hooks are per worker - {@link WalkConsumer#onPassStarted} /
     *  {@link WalkConsumer#onPassCompleted} bracket one worker's share - so a {@code PASS_SNAPSHOT} rebuilder,
     *  committing one artifact from an accumulation over the whole pass, is bracketed correctly only when one worker
     *  drives the whole pass, the degrade {@link RebuildPass} records. Consumers are discovered, so the lease is what
     *  is correct for every delivery class; it costs only scale-out, and a dead node's segments are reclaimed by the
     *  next interval's holder. */
    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    /** The account of the run in flight, summed over the repositories this node walked; written at completion. */
    private final LongAdder repositories = new LongAdder();
    private final LongAdder pointers = new LongAdder();
    private final LongAdder inventory = new LongAdder();
    private final LongAdder blobs = new LongAdder();
    private final LongAdder derived = new LongAdder();

    /** The run is over on this node: record what it cost, deployment-wide, where the walks screen reads it. */
    @Override
    public void completed(Instant started) throws IOException {
        Optional<ArtifactStore> root = Requests.root();
        WalkRuns.Cost cost = new WalkRuns.Cost(name, started, Instant.now(), repositories.sumThenReset(),
                pointers.sumThenReset(), inventory.sumThenReset(), blobs.sumThenReset(), derived.sumThenReset());
        if (root.isPresent()) {
            WalkRuns.record(root.get(), cost);
        }
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        // Every family the deployment can name; the pass walks only those a consumer listens on. Each consumer gets
        // this repository's configuration first, so the repository's own rules govern it here.
        ArtifactStore store = context.store();
        for (WalkConsumer consumer : consumers) {
            consumer.onRepository(store, context.config());
        }
        Optional<WalkPass> pass = RebuildPass.run(walk, store, new Publication(store),
                new RebuildPass.Roots(StoreRepositoryInventory.pointerRoots(),
                        List.of(StoreRepositoryInventory.publishedRoot()), List.of("blobs"),
                        StoreRepositoryInventory.derivedRoots()), consumers, name);
        if (pass.isEmpty()) {
            return;
        }
        repositories.increment();
        RebuildPass.last(context.store(), name).ifPresent(measured -> {
            pointers.add(measured.pointers());
            inventory.add(measured.inventory());
            blobs.add(measured.blobs());
            derived.add(measured.derived());
        });
        // A consumer that failed did so alone: here its failure becomes the pass's outcome - named, counted against
        // this task, and the reason the next pass is asked for.
        UnitFailures failures = context.failures("The rebuild pass of " + context.tenant() + "/"
                + context.repository(), "Each named consumer's projection is incomplete for this generation; the "
                + "next pass redelivers every key to it.");
        for (RebuildPass.Failed failed : RebuildPass.failed(context.store(), name)) {
            if (failed.generation() == pass.get().generation()) {
                failures.record(failed.consumer() + (failed.key() == null ? "" : " at " + failed.key()),
                        new IOException(failed.failure()));
            }
        }
        failures.rethrow();
    }
}
