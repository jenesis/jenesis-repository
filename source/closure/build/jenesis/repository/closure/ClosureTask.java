package build.jenesis.repository.closure;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.inventory.ChangedVersions;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.settings.CoreDefaults;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Resolves the closure of every release that has none, in a repository whose {@value #SETTING} is on: on the
 * {@link IncrementalPasses} cadence, the releases published since the last full pass, and on a full pass every
 * release - which is how a version published before the setting was on is resolved. A closure resolves through the
 * repository and the repositories its fallbacks name ({@link ClosureWalk}), by the first {@link ClosureSource} serving
 * the release's ecosystem that answers. A version is resolved once: its
 * closure is a section of its document ({@link ClosureSection}), and a version that has one is not resolved again.
 *
 * <p>What the closure reaches is followed beside it: the {@link ExposureSection} records each version it reaches that
 * is held for review or carries findings, derived when the closure is resolved and again on every pass that visits the
 * release - every full pass - so a release inherits a later finding or hold on a copy it relies on, and loses one
 * withdrawn or released, without any lookup of its own. It is written only where it changed.
 *
 * <p>Each closure is indexed the other way round too ({@link ReliedOn}): the pass writes a row, in the repository of
 * the walk holding it, for each version the closure reaches, and on a full pass of its own removes the rows of this
 * repository whose dependent no longer relies on what they name. A version here whose findings or holds changed
 * ({@link ChangedVersions}) marks the releases relying on it stale in their own repositories, whose next pass
 * re-derives their exposure first - so a release follows a new finding or hold on a copy it relies on within a pass of
 * each repository, with no feed asked and without waiting for its full pass.
 *
 * <p>Lease-owned, since it writes the version documents; idempotent, since a crash leaves the versions it had not
 * reached without a section, which the next pass reaches. A version whose resolution fails is contained, reported, and
 * left without a section so the next pass tries again; the full-pass stamp then does not advance.
 */
public final class ClosureTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosureTask.class);

    /** The task name - also the {@code locks/closure-resolve} object the pass locks on. */
    public static final String NAME = "closure-resolve";

    /** The repository setting that switches closure resolution on. */
    public static final String SETTING = "closure-resolution";

    /** On, in the form the setting catalogue publishes: a version published here is screened through its closure. */
    public static final String DEFAULT = "true";

    /** The space of the relied-on reconcile's own cadence, beside the resolution's {@code closure/resolve}. */
    private static final String RECONCILE = "closure/reconcile";

    /** How many changed versions, and how many stale releases, one pass takes up; the rest wait for the next. */
    private static final int DRAIN = 1_000;

    private final Duration interval;
    private final List<ClosureSource> sources;

    /** The pass over {@code sources}, asked in the order given - {@link ClosureSource#installed()} in production. */
    public ClosureTask(Duration interval, List<ClosureSource> sources) {
        this.interval = Objects.requireNonNull(interval, "interval");
        this.sources = List.copyOf(sources);
    }

    /** Whether {@code config} resolves closures: on unless it says {@code false}. */
    public static boolean enabled(UnaryOperator<String> config) {
        String value = config == null ? null : config.apply(SETTING);
        return !"false".equalsIgnoreCase((value == null || value.isBlank() ? DEFAULT : value).strip());
    }

    /** The first answer of the sources serving {@code release}'s ecosystem, in their order: a carried bill, then a
     *  resolver, then a scanner, then the declaration walk. */
    private Optional<ClosureSection.Closure> resolve(ClosureWalk through, StoreRepositoryInventory.Coordinate release,
                                                     Instant now) throws IOException {
        for (ClosureSource source : sources) {
            if (!source.ecosystems().contains(release.ecosystem())) {
                continue;
            }
            Optional<ClosureSection.Closure> closure = source.resolve(through, release.ecosystem(),
                    release.coordinate(), release.version(), now);
            if (closure.isPresent()) {
                return closure;
            }
        }
        return Optional.empty();
    }

    /** The band from which a finding of a version the closure reaches counts against it, as the deployment's
     *  {@code vulnerability-risk-threshold} names it. */
    private static Severity riskBand(UnaryOperator<String> config) {
        String band = config == null ? null : config.apply("vulnerability-risk-threshold");
        try {
            return Severity.valueOf((band == null || band.isBlank() ? CoreDefaults.VULNERABILITY_RISK_THRESHOLD
                    : band).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Severity.valueOf(CoreDefaults.VULNERABILITY_RISK_THRESHOLD);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        reconcile(context);
        propagate(context);
        if (!enabled(context.config())) {
            ReliedOn.drainStale(context.store(), DRAIN, _ -> {
            });   // nothing here re-derives, so what was asked of it is dropped rather than kept
            return;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(context.store());
        UnitFailures failed = context.failures("The closure pass of " + context.tenant() + "/" + context.repository(),
                "Those versions have no closure or exposure yet; the next pass resolves them.");
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), NAME, "closure/resolve",
                context.config());
        Visit visit = new Visit(context, MetadataProvider.installed().over(context.store()), ClosureWalk.of(context),
                riskBand(context.config()), failed);
        // What another repository's pass found changed among what these releases reach, re-derived first and with
        // nothing resolved: a release still waiting for its closure is the cadence's below.
        ReliedOn.drainStale(context.store(), DRAIN, release -> visit.release(release, false, false));
        cadence.coordinates(inventory, release -> visit.release(release, true, cadence.full()));
        cadence.completed(context.now(), !failed.any());
        if (visit.resolved > 0 || visit.exposed > 0 || visit.indexed > 0) {
            LOGGER.info("Resolved {} closure(s), re-derived {} exposure(s) and indexed {} relied-on row(s) in {}/{}",
                    visit.resolved, visit.exposed, visit.indexed, context.tenant(), context.repository());
        }
    }

    /** One pass's visit of the releases of one repository, and what it did. */
    private final class Visit {

        private final RepositoryContext context;
        private final MetadataStore metadata;
        private final ClosureWalk through;
        private final Severity risk;
        private final UnitFailures failed;
        private long resolved;
        private long exposed;
        private long indexed;

        private Visit(RepositoryContext context, MetadataStore metadata, ClosureWalk through, Severity risk,
                      UnitFailures failed) {
            this.context = context;
            this.metadata = metadata;
            this.through = through;
            this.risk = risk;
            this.failed = failed;
        }

        /** Resolve {@code release}'s closure where it has none and {@code resolve} says so, re-derive its exposure,
         *  and write its relied-on rows - blind for a closure resolved now, where missing on a {@code full} pass or
         *  where the exposure changed. A failure is contained and reported. */
        private void release(StoreRepositoryInventory.Coordinate release, boolean resolve, boolean full) {
            try {
                Optional<MetadataDocument> document = metadata.read(release.ecosystem(), release.coordinate(),
                        release.version());
                Optional<ClosureSection.Closure> closure = document.flatMap(read -> ClosureSection.closure(
                        read.section(ClosureSection.TAG)));
                boolean fresh = closure.isEmpty();
                if (fresh) {
                    closure = resolve ? ClosureTask.this.resolve(through, release, context.now()) : Optional.empty();
                    if (closure.isEmpty()) {
                        return;     // nothing installed serves the ecosystem; a source installed later resolves it
                    }
                }
                ExposureSection.Exposure derived = Exposures.derive(through, release.ecosystem(), closure.get(), risk,
                        context.now());
                Optional<ExposureSection.Exposure> was = document.flatMap(read -> ExposureSection.exposure(
                        read.section(ExposureSection.TAG)));
                boolean changed = was.isEmpty() || !was.get().sameAs(derived);
                // The rows go before the closure they index, and a pass that finds the closure recorded makes sure of
                // them where they could be missing: a full pass, or an exposure naming a held version anew.
                if (fresh || changed || full) {
                    indexed += ReliedOn.index(through, release.ecosystem(), new ReliedOn.Row(context.repository(),
                            release.coordinate(), release.version()), closure.get(), derived, fresh);
                }
                if (fresh) {
                    metadata.mutate(release.ecosystem(), release.coordinate(), release.version(), ClosureSection.TAG,
                            ClosureSection.record(closure.get()));
                    resolved++;
                }
                if (changed) {
                    metadata.mutate(release.ecosystem(), release.coordinate(), release.version(),
                            ExposureSection.TAG, ExposureSection.record(derived));
                    exposed++;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not resolve the closure of {} {}:{} in {}/{}", release.ecosystem(),
                        release.coordinate(), release.version(), context.tenant(), context.repository(), e);
                failed.record(release.ecosystem() + ' ' + release.coordinate() + ':' + release.version(), e);
            }
        }
    }

    /** Tell the repositories relying on what this one holds that it changed: each version whose findings or holds
     *  moved since the last pass ({@link ChangedVersions}) marks every published version its {@link ReliedOn} rows
     *  name as stale in that version's repository, whose own pass re-derives it. Runs whether or not this repository
     *  resolves closures; a failure is contained and reported, and the full passes there re-derive what it missed. */
    private static void propagate(RepositoryContext context) {
        try {
            ChangedVersions.drain(context.store(), DRAIN, changed -> ReliedOn.dependents(context.store(),
                    changed.ecosystem(), changed.coordinate(), changed.version(), dependent -> {
                        Optional<ArtifactStore> store = dependent.repository().equals(context.repository())
                                ? Optional.of(context.store())
                                : context.repository(dependent.repository()).map(RepositoryContext::store);
                        if (store.isPresent()) {
                            ReliedOn.stale(store.get(), changed.ecosystem(), dependent);
                        }
                    }));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not tell what relies on the changed versions of {}/{}", context.tenant(),
                    context.repository(), e);
            context.failures("The relied-on propagation of " + context.tenant() + "/" + context.repository(),
                    "What relies on those versions is re-derived on its repository's next full pass.")
                    .record("changed", e);
        }
    }

    /** On the reconcile's own full pass, which runs whether or not this repository resolves closures - what it holds
     *  may be relied on by another's - remove the {@link ReliedOn} rows whose dependent no longer relies on what it
     *  names. */
    private static void reconcile(RepositoryContext context) throws IOException {
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), NAME, RECONCILE, context.config());
        if (!cadence.full()) {
            cadence.completed(context.now(), true);
            return;
        }
        boolean clean = true;
        try {
            long removed = ReliedOn.reconcile(context.store(), context.repository(), named -> named.equals(
                    context.repository()) ? Optional.of(context.store())
                    : context.repository(named).map(RepositoryContext::store));
            if (removed > 0) {
                LOGGER.info("Removed {} relied-on row(s) no closure names any more in {}/{}", removed,
                        context.tenant(), context.repository());
            }
        } catch (IOException | RuntimeException e) {
            clean = false;
            LOGGER.warn("Could not reconcile the relied-on rows of {}/{}", context.tenant(), context.repository(), e);
            context.failures("The relied-on reconcile of " + context.tenant() + "/" + context.repository(),
                    "Rows no closure names stay until the next full pass; the reader passes over them.")
                    .record("relied-on", e);
        }
        cadence.completed(context.now(), clean);
    }
}
