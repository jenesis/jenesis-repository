package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled write-role half of &sect;10 for the signal family: it draws whatever a
 * {@link RefreshableSource mirroring signal source} has to draw, so the sources' <em>query</em> paths can render a
 * persisted snapshot and never fetch. Before it existed the one mirroring signal - the CISA known-exploited catalogue -
 * refreshed from inside {@code contains(cve)}, which put a multi-megabyte download on the publish thread and made a
 * gate decision depend on the vendor being reachable at the moment somebody uploaded.
 *
 * <p>Deployment-global, not per repository. A signal source is a deployment singleton by its SPI's own tenant-scoping
 * clause - the CISA catalogue is the same public data for every tenant - so the work happens once per pass in
 * {@link #completed}, after the (empty) repository fan-out, rather than once per {@code (tenant, repository)} unit. A
 * deployment with no repositories at all still refreshes, which matters on the first boot of an empty server.
 *
 * <p>Exclusive: the refresh commits into the deployment-global snapshot space through a compare-and-set, so one node
 * per interval draws the feed and the rest render what it committed. Idempotent by construction - a source inside its
 * own refresh window renders and returns without spending a request - so a short pass interval costs a store read
 * rather than a vendor call, and the interval is about how quickly a <em>cold</em> deployment catches up rather than
 * about how often the vendor is drawn.
 *
 * <p><strong>A failed draw fails the pass.</strong> {@code MaintenanceTask} clause 4 is explicit that a unit which
 * could not do its work must throw rather than return quietly, or an index unrebuilt for a week is indistinguishable
 * from a healthy one. So a source whose refresh did not land is named in an {@link IOException} the scheduler logs and
 * counts on {@code jenreg.maintenance.failures}. The refresh itself stays fail-soft where it must be: the
 * prior-good catalogue keeps serving and the gate keeps deciding, exactly as before - what changed is that an outage
 * is now <em>counted</em> instead of being a silent lazy-load nobody watched.
 */
public final class SignalRefreshTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignalRefreshTask.class);

    /** The passes a changed catalogue sends over every version at once, by task name. */
    public static final List<String> CATALOGUE_DRIVEN = List.of("scan", "kev-enforce", "reanalyze");

    private final Duration interval;
    /** The enabled mirroring sources, keyed by signal name - resolved once by the provider, exactly as the sibling
     *  compliance passes resolve their feeds, because a signal source carries no tenant to re-resolve for. */
    private final Map<String, RefreshableSource> sources;

    public SignalRefreshTask(Duration interval, Map<String, RefreshableSource> sources) {
        this.interval = interval;
        this.sources = Map.copyOf(sources);
    }

    @Override
    public String name() {
        return "signal-refresh";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    /** Nothing per repository: what this pass refreshes is deployment-global. */
    @Override
    public void repository(RepositoryContext context) {
    }

    @Override
    public void completed(Instant started) throws IOException {
        List<String> failed = new ArrayList<>();
        // Name-sorted, and every signal is attempted even when an earlier one raised: a store fault under one mirror
        // must not cost the others their draw, which is the same containment the scheduler gives a repository unit.
        for (Map.Entry<String, RefreshableSource> source : new TreeMap<>(sources).entrySet()) {
            try {
                Optional<String> before = source.getValue().snapshot();
                Optional<Instant> drawnBefore = source.getValue().freshness().refreshed();
                Freshness freshness = source.getValue().refresh();
                if (freshness.authoritative()) {
                    LOGGER.debug("Refreshed the {} signal; its data was drawn at {}", source.getKey(),
                            freshness.refreshed().map(Instant::toString).orElse("an unrecorded instant"));
                    Optional<String> after = source.getValue().snapshot();
                    // Changed when the committed snapshot's digest moved; a source with no durable snapshot to
                    // compare counts every draw that landed as a change, since it cannot say otherwise.
                    boolean changed = after.isPresent() ? !after.equals(before)
                            : freshness.refreshed().isPresent() && !freshness.refreshed().equals(drawnBefore);
                    if (changed) {
                        // The catalogue changed: the passes that read only what was published since their last full
                        // pass are asked, by name, for a full one - a listing or a delisting names old versions.
                        for (String pass : CATALOGUE_DRIVEN) {
                            Requests.requestOnRoot(pass, "the " + source.getKey() + " catalogue changed");
                        }
                    }
                } else {
                    failed.add(source.getKey() + " (vendor unreachable; last drawn "
                            + freshness.refreshed().map(Instant::toString).orElse("never") + ")");
                }
            } catch (Throwable e) {
                // The durable side, not the vendor: a wiring or infrastructure fault, which the role's contract
                // distinguishes precisely so it does not read as "the feed is down".
                //
                // Throwable rather than IOException | RuntimeException. An Error out of one mirror is the
                // likeliest failure a plugged-in feed module actually produces - a NoClassDefFoundError from a
                // half-installed optional dependency - and it used to leave this loop, so the mirrors sorted after
                // it never drew at all. an earlier change means the scheduler survives that, which is precisely what made it
                // quiet: the pass was counted as failing without ever naming which signal, and every OTHER signal's
                // catalogue stayed undrawn for the life of the process while the gate kept screening against it.
                // Containing it here is not swallowing it - the earlier ruling is that an Error is attributed rather
                // than filed as the guest's answer, and the escalation question is decided by who the caller is:
                // this method's caller is the maintenance worker, which has none, so the escalation goes to the
                // operator through the named failure below exactly as the scheduler's own does. The name is the map
                // key captured when the pass was built, never read back off the mirror that just gave way.
                LOGGER.warn("Could not commit the {} signal's snapshot", source.getKey(), e);
                failed.add(source.getKey() + " (" + e + ")");
            }
        }
        if (!failed.isEmpty()) {
            // Named, not merely counted: the message says which signals are stale, so an operator reading the failure
            // counter can act on it rather than only knowing that "something" did not refresh.
            throw new IOException("Could not refresh " + String.join(", ", failed)
                    + "; the prior-good data keeps serving and the pass is retried on the next interval");
        }
    }
}
