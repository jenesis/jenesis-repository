package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled write half of "reads render, writes refresh" for the signal family: it draws what a
 * {@link RefreshableSource mirroring signal source} has to draw, so the query paths render a persisted snapshot and
 * never fetch on the publish thread.
 *
 * <p>Deployment-global, since a signal source is a deployment singleton: the work happens once per pass in
 * {@link #completed}, so a deployment with no repositories still refreshes.
 *
 * <p>Exclusive: the refresh commits into the global snapshot space through a compare-and-set, so one node per interval
 * draws. A source inside its own refresh window returns without a request, so a short interval costs a store read
 * rather than a vendor call.
 *
 * <p><strong>A failed draw fails the pass</strong> (clause 4): the source is named in an {@link IOException} the
 * scheduler logs and counts, while the prior-good catalogue keeps serving.
 */
public final class SignalRefreshTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignalRefreshTask.class);

    /** The passes a changed catalogue sends over every version at once, by task name. */
    public static final List<String> CATALOGUE_DRIVEN = List.of("scan", "kev-enforce", "reanalyze");

    private final Duration interval;
    /** The enabled mirroring sources, keyed by signal name, resolved once since a signal source carries no tenant. */
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
        // Every signal is attempted even when an earlier one raised, so a fault under one mirror does not cost the
        // others their draw.
        for (Map.Entry<String, RefreshableSource> source : new TreeMap<>(sources).entrySet()) {
            try {
                Optional<String> before = source.getValue().snapshot();
                Optional<Instant> drawnBefore = source.getValue().freshness().refreshed();
                Freshness freshness = source.getValue().refresh();
                if (freshness.authoritative()) {
                    LOGGER.debug("Refreshed the {} signal; its data was drawn at {}", source.getKey(),
                            freshness.refreshed().map(Instant::toString).orElse("an unrecorded instant"));
                    Optional<String> after = source.getValue().snapshot();
                    // Changed when the snapshot digest moved; a source with no durable snapshot counts every landed
                    // draw as a change.
                    boolean changed = after.isPresent() ? !after.equals(before)
                            : freshness.refreshed().isPresent() && !freshness.refreshed().equals(drawnBefore);
                    if (changed) {
                        // A changed catalogue asks the incremental passes for a full one, since a listing or delisting
                        // names old versions.
                        for (String pass : CATALOGUE_DRIVEN) {
                            Requests.requestOnRoot(pass, "the " + source.getKey() + " catalogue changed");
                        }
                    }
                } else {
                    failed.add(source.getKey() + " (vendor unreachable; last drawn "
                            + freshness.refreshed().map(Instant::toString).orElse("never") + ")");
                }
            } catch (Throwable e) {
                // The durable side failed, not the vendor. Throwable is caught because a plugged-in feed's likeliest
                // failure is an Error such as a NoClassDefFoundError from a half-installed dependency, and leaving the
                // loop would leave every later signal undrawn. It is not swallowed: it is named in the failure raised
                // below.
                LOGGER.warn("Could not commit the {} signal's snapshot", source.getKey(), e);
                failed.add(source.getKey() + " (" + e + ")");
            }
        }
        if (!failed.isEmpty()) {
            // Named, so an operator knows which signals are stale.
            throw new IOException("Could not refresh " + String.join(", ", failed)
                    + "; the prior-good data keeps serving and the pass is retried on the next interval");
        }
    }
}
