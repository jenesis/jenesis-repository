package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.RepositorySelection;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
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
 * <p>It also draws what each advisory feed that publishes its changes ({@link AdvisorySource.Changes}) changed since its
 * last draw into that feed's change log, which the scan pass reads to ask again about the packages whose records
 * changed.
 *
 * <p>A feed that keeps a copy of its vendor's records ({@link AdvisorySource.Mirror}) is told before it refreshes which
 * ecosystems to keep: those of the repositories naming it in {@value AdvisorySource#SELECTION}, gathered from every
 * repository this pass visits - one settings lookup and, for a repository naming a mirror, one read of its
 * definition.
 *
 * <p><strong>A failed draw fails the pass</strong> (clause 4): the source is named in an {@link IOException} the
 * scheduler logs and counts, while the prior-good catalogue keeps serving.
 *
 * <p>What the pass found - each source's freshness, why a refresh failed, each mirror's copies - is recorded as the
 * {@link SignalStatus} document the operator surfaces read back, whether or not the pass failed.
 */
public final class SignalRefreshTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignalRefreshTask.class);

    /** The passes a changed catalogue sends over every version at once, by task name. */
    public static final List<String> CATALOGUE_DRIVEN = List.of("scan", "kev-enforce", "reanalyze");

    private final Duration interval;
    /** The enabled mirroring sources, keyed by signal name, resolved once since a signal source carries no tenant. */
    private final Map<String, RefreshableSource> sources;
    /** The enabled advisory feeds that publish their changes, keyed by signal name. */
    private final Map<String, AdvisorySource.Changes> changes;
    /** The ecosystems each mirror is named for by the repositories this pass has visited, by signal name; emptied as
     *  the pass completes. */
    private final Map<String, Set<String>> wanted = new ConcurrentHashMap<>();
    /** The names of the enabled sources that keep a copy of their vendor's records. */
    private final Set<String> mirrors;
    /** Where the pass records what it found, resolved when it records; {@code null} records nothing. */
    private final Supplier<ArtifactStore> status;

    public SignalRefreshTask(Duration interval, Map<String, RefreshableSource> sources) {
        this(interval, sources, Map.of(), null);
    }

    public SignalRefreshTask(Duration interval, Map<String, RefreshableSource> sources,
                             Map<String, AdvisorySource.Changes> changes) {
        this(interval, sources, changes, null);
    }

    /** @param status the {@link SignalStatus#space space} the pass records what it found in, or {@code null} for a
     *               composition that keeps no record. */
    public SignalRefreshTask(Duration interval, Map<String, RefreshableSource> sources,
                             Map<String, AdvisorySource.Changes> changes, Supplier<ArtifactStore> status) {
        this.interval = interval;
        this.sources = Map.copyOf(sources);
        this.changes = Map.copyOf(changes);
        this.status = status;
        this.mirrors = this.sources.entrySet().stream()
                .filter(source -> source.getValue() instanceof AdvisorySource.Mirror)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
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

    /** What this pass refreshes is deployment-global; a repository only says which ecosystems the mirrors it names
     *  are to keep. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        if (mirrors.isEmpty()) {
            return;
        }
        Set<String> named = new LinkedHashSet<>();
        for (String name : RepositorySelection.named(context.config().apply(AdvisorySource.SELECTION))) {
            if (mirrors.contains(name)) {
                named.add(name);
            }
        }
        if (named.isEmpty()) {
            return;
        }
        Set<String> ecosystems = RepositoryDocument.read(context.store())
                .flatMap(document -> RepositoryType.installed(document.format()))
                .map(RepositoryType::ecosystems).orElse(Set.of());
        for (String name : named) {
            wanted.computeIfAbsent(name, _ -> ConcurrentHashMap.newKeySet()).addAll(ecosystems);
        }
    }

    @Override
    public void completed(Instant started) throws IOException {
        List<String> failed = new ArrayList<>();
        // Why each source's refresh failed this pass, by signal name, for the record the surfaces read.
        Map<String, String> reasons = new TreeMap<>();
        // Every signal is attempted even when an earlier one raised, so a fault under one mirror does not cost the
        // others their draw.
        Map<String, Set<String>> mirrored = new TreeMap<>();
        for (String name : List.copyOf(wanted.keySet())) {
            mirrored.put(name, Set.copyOf(wanted.remove(name)));
        }
        for (Map.Entry<String, RefreshableSource> source : new TreeMap<>(sources).entrySet()) {
            try {
                if (source.getValue() instanceof AdvisorySource.Mirror mirror) {
                    mirror.mirror(mirrored.getOrDefault(source.getKey(), Set.of()));
                }
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
                    reasons.put(source.getKey(), "the vendor could not be reached");
                }
            } catch (Throwable e) {
                // The durable side failed, not the vendor. Throwable is caught because a plugged-in feed's likeliest
                // failure is an Error such as a NoClassDefFoundError from a half-installed dependency, and leaving the
                // loop would leave every later signal undrawn. It is not swallowed: it is named in the failure raised
                // below.
                LOGGER.warn("Could not commit the {} signal's snapshot", source.getKey(), e);
                failed.add(source.getKey() + " (" + e + ")");
                reasons.put(source.getKey(), "its snapshot could not be committed: " + e);
            }
        }
        for (Map.Entry<String, AdvisorySource.Changes> feed : new TreeMap<>(changes).entrySet()) {
            try {
                int named = feed.getValue().drawChanges();
                if (named > 0) {
                    LOGGER.info("The {} feed changed records of {} package(s) since its last draw", feed.getKey(),
                            named);
                }
            } catch (Throwable e) {
                LOGGER.warn("Could not draw what the {} feed changed", feed.getKey(), e);
                failed.add(feed.getKey() + " changes (" + e + ")");
                reasons.merge(feed.getKey(), "its changes could not be drawn: " + e, (a, b) -> a + "; " + b);
            }
        }
        record(reasons);
        if (!failed.isEmpty()) {
            // Named, so an operator knows which signals are stale.
            throw new IOException("Could not refresh " + String.join(", ", failed)
                    + "; the prior-good data keeps serving and the pass is retried on the next interval");
        }
    }

    /** Record what each source holds after this pass as the {@link SignalStatus} document; best-effort, since it is a
     *  report: a failure to write it is logged and leaves the previous record standing, as of the instant it says. */
    private void record(Map<String, String> reasons) {
        if (status == null) {
            return;
        }
        try {
            List<SignalStatus.Source> recorded = new ArrayList<>();
            for (Map.Entry<String, RefreshableSource> source : new TreeMap<>(sources).entrySet()) {
                Freshness freshness = source.getValue().freshness();
                List<AdvisorySource.Mirror.Copy> copies = source.getValue() instanceof AdvisorySource.Mirror mirror
                        ? mirror.copies() : List.of();
                recorded.add(new SignalStatus.Source(source.getKey(), freshness.refreshed().orElse(null),
                        freshness.authoritative(), reasons.get(source.getKey()), copies));
            }
            SignalStatus.write(status.get(), new SignalStatus.Status(Instant.now(), recorded));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not record what the signal-refresh pass found; the previous record stands", e);
        }
    }
}
