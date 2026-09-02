package build.jenesis.repository.server;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;

/**
 * The free edition's scheduled driver of the shared {@link RebuildPass}: on a cadence it joins the pass over the
 * deployment's one repository and streams every retained pointer to every discovered {@link WalkConsumer}, so a
 * consumer's view (a format's module index, a back-fill) converges on its own without an embedder driving it and
 * without waiting for the artifact to be republished. The cadence is {@code jenreg.rebuild.interval} (a day by
 * default; {@code off} or {@code 0} switches the driver off), the first pass runs a minute after boot. The driver is
 * inert - it logs one line and schedules nothing - when there is no walk with a consumer and no listing to repair, so
 * a deployment without either is byte-for-byte unchanged. One pass at a time: a cadence tick that finds the previous
 * pass still running is skipped, never stacked. Its status is reported through {@link Observability}, the discovered
 * {@link ObservabilitySource} the report lists it under.
 */
public final class RebuildScheduler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildScheduler.class);

    /** The cadence setting: an ISO-8601 duration ({@code PT6H}), a simple one ({@code 6h}, {@code 30m}), or
     *  {@code off}/{@code 0}. */
    public static final String INTERVAL = "jenreg.rebuild.interval";

    /** The first pass runs this long after boot, so a restart does not walk the store before it serves. */
    static final Duration INITIAL_DELAY = Duration.ofMinutes(1);

    private static final String TASK = "jenreg.rebuild.pass";

    private static final AtomicReference<RebuildScheduler> INSTALLED = new AtomicReference<>();

    private final ArtifactStore store;
    private final Duration interval;
    private final Optional<ArtifactWalk> walk;
    private final List<WalkConsumer> consumers;
    private final List<StoredListing.Rebuilder> rebuilders;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastRun;
    private volatile Duration lastDuration;
    private volatile String lastOutcome = "";
    private volatile boolean lastFailed;

    public RebuildScheduler(ArtifactStore store, UnaryOperator<String> config) {
        this(store, config, WalkProvider.resolve(config), WalkConsumer.discovered(), rebuilders());
    }

    /** The explicit seam: the walk and the consumers handed in rather than discovered, and no listing repair. */
    public RebuildScheduler(ArtifactStore store, UnaryOperator<String> config, Optional<ArtifactWalk> walk,
                            List<WalkConsumer> consumers) {
        this(store, config, walk, consumers, List.of());
    }

    /** The explicit seam with the listing repairers handed in as well. */
    public RebuildScheduler(ArtifactStore store, UnaryOperator<String> config, Optional<ArtifactWalk> walk,
                            List<WalkConsumer> consumers, List<StoredListing.Rebuilder> rebuilders) {
        this.store = Objects.requireNonNull(store, "store");
        this.interval = interval(config.apply(INTERVAL));
        this.walk = Objects.requireNonNull(walk, "walk");
        this.consumers = List.copyOf(consumers);
        this.rebuilders = List.copyOf(rebuilders);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jenesis-rebuild");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Whether this driver schedules anything: a cadence, and a walk with at least one consumer or a listing to
     *  repair. */
    public boolean active() {
        return !interval.isZero() && ((walk.isPresent() && !consumers.isEmpty()) || !rebuilders.isEmpty());
    }

    /** The stored-listing repairers among the discovered observers - every format that maintains a listing. */
    public static List<StoredListing.Rebuilder> rebuilders() {
        List<StoredListing.Rebuilder> discovered = new ArrayList<>();
        for (PublicationObserver observer : ServiceLoader.load(PublicationObserver.class)) {
            if (observer instanceof StoredListing.Rebuilder rebuilder) {
                discovered.add(rebuilder);
            }
        }
        return List.copyOf(discovered);
    }

    public Duration interval() {
        return interval;
    }

    public void start() {
        INSTALLED.set(this);
        if (interval.isZero()) {
            LOGGER.info("rebuild pass: off ({}=off); the discovered walk consumers converge only when a pass is "
                    + "driven by an embedder or an artifact is republished", INTERVAL);
            return;
        }
        if ((walk.isEmpty() || consumers.isEmpty()) && rebuilders.isEmpty()) {
            LOGGER.info("rebuild pass: no artifact walk with a consumer and no listing to repair, nothing scheduled");
            return;
        }
        LOGGER.info("rebuild pass: every {} over {} consumer(s) and {} listing repairer(s), first in {}", interval,
                consumers.size(), rebuilders.size(), INITIAL_DELAY);
        scheduler.scheduleAtFixedRate(this::runQuietly, INITIAL_DELAY.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Drive one pass now and answer it as this worker last saw it - the test seam and what the cadence runs;
     *  empty when the driver has nothing to run or a pass is already running. */
    public Optional<WalkPass> runNow() throws IOException {
        if (((walk.isEmpty() || consumers.isEmpty()) && rebuilders.isEmpty()) || !running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        Instant started = Instant.now();
        try {
            Optional<WalkPass> pass = walk.isEmpty() || consumers.isEmpty() ? Optional.empty()
                    : RebuildPass.run(walk.get(), store, roots(), consumers);
            // The stored listings are repaired after the walk: every document regenerated through the format that
            // owns it, so a drift an incremental write could have left is corrected on this cadence, never by a read.
            int rebuilt = rebuilders.isEmpty() ? 0 : StoredListing.rebuildAll(store, rebuilders);
            lastOutcome = pass.map(p -> p.complete() ? "complete" : "joined, other workers still active")
                    .orElse("nothing to walk") + (rebuilt > 0 ? ", " + rebuilt + " listing(s) regenerated" : "");
            lastFailed = false;
            return pass;
        } catch (IOException | RuntimeException failure) {
            lastOutcome = "failed: " + failure.getClass().getSimpleName();
            lastFailed = true;
            throw failure;
        } finally {
            lastRun = started;
            lastDuration = Duration.between(started, Instant.now());
            running.set(false);
        }
    }

    private void runQuietly() {
        try {
            runNow();
        } catch (IOException | RuntimeException failure) {
            // The next cadence resumes the pass from its last committed cursor; a failure delays a rebuild, it never
            // truncates one, and it is visible on the status rather than on the node's health.
            LOGGER.warn("rebuild pass failed; the next cadence resumes it: {}", failure.toString());
        }
    }

    /** The status the report shows: disabled when nothing is scheduled, else the last pass and its outcome. */
    public TaskStatus status() {
        String description = "The scheduled rebuild pass streaming every retained pointer to the discovered walk "
                + "consumers.";
        if (!active()) {
            return new TaskStatus(TASK, description, TaskStatus.State.DISABLED, null, null,
                    interval.isZero() ? "off" : walk.isEmpty() ? "no artifact walk installed"
                            : "no walk consumer discovered");
        }
        if (running.get()) {
            return TaskStatus.ran(TASK, description, TaskStatus.State.RUNNING, lastRun, lastDuration, "running");
        }
        if (lastRun == null) {
            return TaskStatus.idle(TASK, description);
        }
        return TaskStatus.ran(TASK, description, lastFailed ? TaskStatus.State.FAILED : TaskStatus.State.IDLE,
                lastRun, lastDuration, lastOutcome);
    }

    /** The pointer roots the pass walks: the free {@code publish} namespace and every blobs-namespace root the
     *  installed formats declare. */
    static List<String> roots() {
        List<String> roots = new ArrayList<>();
        roots.add("publish");
        for (BlobReferences lender : BlobReferences.installed()) {
            roots.addAll(lender.blobRoots());
        }
        return roots;
    }

    public static Duration interval(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofDays(1);
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("off") || text.equals("0") || text.equals("false")) {
            return Duration.ZERO;
        }
        try {
            if (text.startsWith("p")) {
                return Duration.parse(text.toUpperCase(Locale.ROOT));
            }
            long amount = Long.parseLong(text.substring(0, text.length() - 1));
            return switch (text.charAt(text.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException(text);
            };
        } catch (RuntimeException unparseable) {
            throw new IllegalArgumentException(INTERVAL + " must be an ISO-8601 duration (PT6H), a simple one "
                    + "(6h, 30m, 1d) or 'off', not '" + value + "'");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** The discovered observability face: the driver's task status, reported from the started instance. */
    public static final class Observability implements ObservabilitySource {

        public Observability() {
        }

        @Override
        public List<TaskStatus> taskStatuses() {
            RebuildScheduler installed = INSTALLED.get();
            return installed == null ? List.of() : List.of(installed.status());
        }
    }
}
