package build.jenesis.repository.closure;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;

/**
 * Resolves the closure of every release that has none, in a repository whose {@value #SETTING} is on: on the
 * {@link IncrementalPasses} cadence, the releases published since the last full pass, and on a full pass every
 * release - which is how a version published before the setting was on is resolved. A closure resolves through the
 * repository and the repositories its fallbacks name ({@link ClosureWalk}), by the first {@link ClosureSource} serving
 * the release's ecosystem that answers. A version is resolved once: its
 * closure is a section of its document ({@link ClosureSection}), and a version that has one is passed by.
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
        if (!enabled(context.config())) {
            return;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(context.store());
        MetadataStore metadata = MetadataProvider.installed().over(context.store());
        ClosureWalk through = ClosureWalk.of(context);
        UnitFailures failed = context.failures("The closure pass of " + context.tenant() + "/" + context.repository(),
                "Those versions have no closure yet; the next pass resolves them.");
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), NAME, "closure/resolve",
                context.config());
        long[] resolved = {0};
        cadence.coordinates(inventory, release -> {
            if (metadata.section(release.ecosystem(), release.coordinate(), release.version(), ClosureSection.TAG)
                    .isPresent()) {
                return;
            }
            try {
                Optional<ClosureSection.Closure> answered = resolve(through, release, context.now());
                if (answered.isEmpty()) {
                    return;     // nothing installed serves the ecosystem; a source installed later resolves it
                }
                ClosureSection.Closure closure = answered.get();
                metadata.mutate(release.ecosystem(), release.coordinate(), release.version(), ClosureSection.TAG,
                        ClosureSection.record(closure));
                resolved[0]++;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not resolve the closure of {} {}:{} in {}/{}", release.ecosystem(),
                        release.coordinate(), release.version(), context.tenant(), context.repository(), e);
                failed.record(release.ecosystem() + ' ' + release.coordinate() + ':' + release.version(), e);
            }
        });
        cadence.completed(context.now(), !failed.any());
        if (resolved[0] > 0) {
            LOGGER.info("Resolved {} closure(s) in {}/{}", resolved[0], context.tenant(), context.repository());
        }
    }
}
