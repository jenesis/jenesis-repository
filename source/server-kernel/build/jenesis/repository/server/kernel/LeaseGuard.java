package build.jenesis.repository.server.kernel;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Lease;

/**
 * The maintenance scheduler's <em>single-writer guard</em>, split out of {@link MaintenanceScheduler} so the
 * acquire &rarr; renew &rarr; run &rarr; release cycle around a {@link Lease} is one object a contention test can drive
 * directly as well as through a live pass (the maintenance-kernel spike's R7).
 *
 * <p>It is deliberately <strong>store-light and tenant-blind</strong>: it knows a lease object, a holder id, a ttl and
 * a renewal timer, and nothing about tenants, repositories, tasks or meters. It is <strong>not a second exclusion
 * mechanism</strong> - it is the same one {@link Lease} the scheduler always took, with its lifecycle named. The
 * deployment's other distributed claim (the free walk's per-segment claim) operates at a different granularity and
 * composes with this one; nothing here duplicates it.
 *
 * <h2>What a lost lease means</h2>
 * A renewal that returns {@code false} means this node stalled past its own ttl and a rival legitimately took the lock:
 * two sweepers are now live over one store. Before that was <em>logged only</em> - the pass ran to completion,
 * its failure counter and task status untouched, so the dashboard showed a clean sweep while the fleet double-swept.
 * The semantics chosen here, and the reason:
 * <ol>
 *   <li><b>It is a pass failure, not a skip.</b> {@link Holding#lost()} flips and stays flipped, and the caller counts
 *       the pass as failed - so {@code jenreg.maintenance.failures} rises and the task reports FAILED.
 *       A refused <em>acquire</em> stays what it always was (a normal, uncounted skip in a fleet); losing a lease you
 *       already held is not that.</li>
 *   <li><b>The pass stops enlarging the window.</b> The caller polls {@link Holding#lost()} between fan-out batches and
 *       submits no further work, bounding the double-sweep to the units already in flight.</li>
 *   <li><b>It is not cancellation.</b> A {@link build.jenesis.repository.maintenance.MaintenanceTask} has no
 *       cancellation seam, so an in-flight unit runs to completion. That residual window is the honest claim
 *       (design gate 5) - and the lease was always best-effort over the store's compare-and-set anyway.</li>
 * </ol>
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Safe for concurrent calls on distinct names and on the same name: {@link Lease#acquire}
 *       refuses a live lease regardless of holder, including this node's own, so an admin run overlapping the worker on
 *       one node is refused rather than doubled. Each call gets its own {@link Holding}; nothing is shared between
 *       passes but the renewal timer thread.</li>
 *   <li><b>Absence sentinel.</b> An empty {@link Optional} means "not acquired - a rival (or this node's own live pass)
 *       holds it"; it never means the body ran and returned nothing. A body returning {@code null} is a programming
 *       error and fails loudly rather than being reported as a refused acquisition.</li>
 *   <li><b>Selection failure (&sect;9).</b> A degenerate ttl - non-positive, or so short that the renewal cadence
 *       ({@code ttl/2}) rounds to zero - is refused at construction naming {@code cleanup-lease}. It is operator input
 *       that removes single-writer exclusion outright <em>and</em> makes every exclusive pass throw; a silent fallback
 *       would leave a deployment believing it has a lease it does not have.</li>
 *   <li><b>Error visibility.</b> A failed renewal and a failed release are logged and (for renewal) surfaced through
 *       {@link Holding#lost()}; neither aborts the body. A release that cannot land leaves the lease to lapse on its
 *       own ttl - the pre-release behaviour, no worse.</li>
 *   <li><b>Lifecycle / ownership.</b> The guard owns one daemon timer thread for renewals and closes it in
 *       {@link #close()}; the caller owns the store. Renewal is scheduled at {@code ttl/2} and cancelled in a
 *       {@code finally}, so a completed pass leaves no timer behind.</li>
 * </ol>
 */
public final class LeaseGuard implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeaseGuard.class);

    /** The operator-facing dial the ttl comes from - named in the refusal so a degenerate value points at its key. */
    private static final String TTL_KEY = "jenreg.cleanup-lease";

    private final Lease lease;
    private final String holder;
    /** How often a long exclusive pass refreshes its lease - half the ttl, so the expiry always stays ahead of the
     *  wall clock while the pass runs and a rival is refused throughout. */
    private final Duration renewInterval;
    /** The one timer thread that renews an exclusive pass's lease on a wall-clock cadence while the pass runs - a
     *  single slow (tenant, repository) unit can outlive the ttl, so renewal must not depend on unit boundaries. */
    private final ScheduledExecutorService renewer;

    LeaseGuard(ArtifactStore root, Duration ttl) {
        this(root, ttl, node());
    }

    LeaseGuard(ArtifactStore root, Duration ttl, String holder) {
        // A non-positive ttl is not a "no exclusion" mode: it makes every acquire immediately steal-able (removing
        // single-writer exclusion outright) AND makes scheduleAtFixedRate throw on every exclusive pass, which the
        // worker then counts as a task failure. One dial, two silent degradations - so it fails fast, naming the key.
        if (ttl == null || !ttl.isPositive() || ttl.dividedBy(2).toMillis() < 1L) {
            throw new IllegalArgumentException(TTL_KEY + "=" + ttl + " is not a usable maintenance lease: it must be a "
                    + "positive duration of at least PT0.002S (the renewal cadence is half the ttl and must be at least "
                    + "a millisecond). A lease of zero or less removes single-writer exclusion entirely - every "
                    + "acquisition is immediately steal-able - and makes every exclusive maintenance pass fail. Keep it "
                    + "comfortably under the task intervals, e.g. PT10M.");
        }
        this.lease = new Lease(root, ttl);
        this.holder = holder;
        this.renewInterval = ttl.dividedBy(2);
        this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread timer = new Thread(runnable, "jenesis-repository-maintenance-lease");
            timer.setDaemon(true);
            return timer;
        });
    }

    /** This node's lease holder id - hostname plus a per-incarnation uuid, so a restarted node never mistakes a
     *  previous incarnation's lease for its own. The hostname is the one the operating system was given, read from
     *  {@code HOSTNAME} when the resolver cannot map it to an address: a container named by its deployment on a host
     *  network has a name nothing resolves, and a fleet's lease holders all read {@code node/...} before this, which
     *  told an operator nothing about which node holds a pass. */
    private static String node() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
            String named = System.getenv("HOSTNAME");
            host = named == null || named.isBlank() ? "node" : named.strip();
        }
        return host + "/" + UUID.randomUUID();
    }

    /** The lease object one named pass locks on - {@code locks/<task-name>} at the deployment root. Stable across
     *  versions on purpose: a renamed task in a mixed-version fleet would lock on a different object and sweep twice. */
    public static String object(String name) {
        return Lease.objectKey(name);
    }

    /** This guard's holder id, so a test can seed a rival lease under a different holder. */
    String holder() {
        return holder;
    }

    /**
     * Run {@code body} while holding the named single-writer maintenance lease: acquired up front (an empty return -
     * and no run - when another holder's lease is live), renewed on a wall-clock cadence shorter than the ttl while the
     * body runs (so a single long unit never lets the lease lapse mid-pass and hand a rival a second, concurrent
     * sweep), and released on completion so the next acquirer takes the lock at once instead of waiting out the ttl.
     * The body is handed the live {@link Holding} so a long fan-out can stop enlarging the double-sweep window the
     * moment single-writer status is lost.
     */
    <T> Optional<T> exclusively(String name, Instant now, Exclusive<T> body) throws IOException {
        if (!lease.acquire(name, holder, now)) {
            return Optional.empty();
        }
        Pass pass = new Pass(name);
        ScheduledFuture<?> renewal = renewer.scheduleAtFixedRate(pass::renew,
                renewInterval.toMillis(), renewInterval.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return Optional.of(body.run(pass));
        } finally {
            renewal.cancel(false);
            try {
                lease.release(name, holder, now);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not release maintenance lease '{}'; it lapses on its own ttl", name, e);
            }
        }
    }

    @Override
    public void close() {
        renewer.shutdownNow();
    }

    /** A value-returning body run under the single-writer maintenance lease, handed the live {@link Holding} so it can
     *  bound its own work once single-writer status is lost. It must return a non-null result: the empty
     *  {@link Optional} the caller receives signals only that a rival holds the lease. */
    interface Exclusive<T> {

        T run(Holding holding) throws IOException;
    }

    /** The live single-writer status of one running pass: {@code true} once a renewal was refused, meaning a rival took
     *  the lock and this pass is no longer the only sweeper. Never resets - a pass that lost the lease has lost it. */
    interface Holding {

        boolean lost();
    }

    /** One running pass's renewal state. Package-private and per-call, so two overlapping passes (an admin run and the
     *  worker, on different task names) never share a lost flag. */
    private final class Pass implements Holding {

        private final String name;
        private final AtomicBoolean lost = new AtomicBoolean();

        private Pass(String name) {
            this.name = name;
        }

        @Override
        public boolean lost() {
            return lost.get();
        }

        /** One wall-clock lease renewal. A refused renewal (the lease was stolen after a stall past the ttl) is not
         *  forced - stealing back would clobber the legitimate new holder - but it is recorded and logged, because two
         *  live sweepers is exactly the state the lease exists to prevent. */
        private void renew() {
            try {
                if (!lease.renew(name, holder, Instant.now())) {
                    if (lost.compareAndSet(false, true)) {
                        LOGGER.warn("Maintenance lease '{}' was lost mid-pass; a rival node may be sweeping "
                                + "concurrently. The pass is counted as failed and stops submitting further units.",
                                name);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A transient store error is not proof the lock was taken, so it does not flip the flag: the lease is
                // still ours until a renewal actually reports otherwise, or until the ttl lapses.
                LOGGER.warn("Could not renew maintenance lease '{}'; it lapses on its own ttl", name, e);
            }
        }
    }
}
