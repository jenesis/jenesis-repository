package build.jenesis.repository.closure;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.settings.CoreDefaults;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;

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
 * repository whose dependent no longer relies on what they name.
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
        if (!enabled(context.config())) {
            return;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(context.store());
        MetadataStore metadata = MetadataProvider.installed().over(context.store());
        ClosureWalk through = ClosureWalk.of(context);
        Severity risk = riskBand(context.config());
        UnitFailures failed = context.failures("The closure pass of " + context.tenant() + "/" + context.repository(),
                "Those versions have no closure or exposure yet; the next pass resolves them.");
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), NAME, "closure/resolve",
                context.config());
        long[] resolved = {0};
        long[] exposed = {0};
        long[] indexed = {0};
        cadence.coordinates(inventory, release -> {
            try {
                Optional<MetadataDocument> document = metadata.read(release.ecosystem(), release.coordinate(),
                        release.version());
                Optional<ClosureSection.Closure> closure = document.flatMap(read -> ClosureSection.closure(
                        read.section(ClosureSection.TAG)));
                boolean fresh = closure.isEmpty();
                if (fresh) {
                    closure = resolve(through, release, context.now());
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
                if (fresh || changed || cadence.full()) {
                    indexed[0] += ReliedOn.index(through, release.ecosystem(), new ReliedOn.Row(context.repository(),
                            release.coordinate(), release.version()), closure.get(), derived, fresh);
                }
                if (fresh) {
                    metadata.mutate(release.ecosystem(), release.coordinate(), release.version(), ClosureSection.TAG,
                            ClosureSection.record(closure.get()));
                    resolved[0]++;
                }
                if (changed) {
                    metadata.mutate(release.ecosystem(), release.coordinate(), release.version(),
                            ExposureSection.TAG, ExposureSection.record(derived));
                    exposed[0]++;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not resolve the closure of {} {}:{} in {}/{}", release.ecosystem(),
                        release.coordinate(), release.version(), context.tenant(), context.repository(), e);
                failed.record(release.ecosystem() + ' ' + release.coordinate() + ':' + release.version(), e);
            }
        });
        cadence.completed(context.now(), !failed.any());
        if (resolved[0] > 0 || exposed[0] > 0 || indexed[0] > 0) {
            LOGGER.info("Resolved {} closure(s), re-derived {} exposure(s) and indexed {} relied-on row(s) in {}/{}",
                    resolved[0], exposed[0], indexed[0], context.tenant(), context.repository());
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
