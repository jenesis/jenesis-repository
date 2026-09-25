package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@link RepositoryContext} a fixture's repair leg runs its real pass under.
 *
 * <p>The point of the whole exercise is that {@code repair(store)} <em>runs the sweep the product ships</em> rather
 * than re-deriving the surface by hand, so the fixtures reach the passes through the one seam the scheduler reaches
 * them through - {@code MaintenanceTask.repository(RepositoryContext)}. Nothing here is a stand-in for the task: the
 * context carries the scoped store, a clock the caller controls (a rebase decision is a wall-clock disjunct, so a
 * repair that must force one needs to move the clock rather than wait), the effective config lookup and a gauge sink
 * that drops its rows.
 */
public record Pass(ArtifactStore store, Instant now, UnaryOperator<String> config) implements RepositoryContext {

    @Override
    public UnitFailures failures(String work, String consequence) {
        return new UnitFailures(work, consequence);
    }

    /** A pass over {@code store} at a fixed instant with no configuration set - what a task reads defaults for. */
    public static Pass over(ArtifactStore store) {
        return new Pass(store, Instant.parse("2026-08-11T00:00:00Z"), _ -> null);
    }

    /** The same pass at a later instant, for a sweep whose due-check is a wall-clock disjunct. */
    public Pass at(Instant later) {
        return new Pass(store, later, config);
    }

    /** The same pass with one configuration key set. */
    public Pass with(String key, String value) {
        UnaryOperator<String> outer = config;
        return new Pass(store, now, asked -> asked.equals(key) ? value : outer.apply(asked));
    }

    @Override
    public String tenant() {
        return "acme";
    }

    @Override
    public String repository() {
        return "main";
    }

    @Override
    public void gauge(String name, String description, Map<String, String> tags, double value) {
    }
}
