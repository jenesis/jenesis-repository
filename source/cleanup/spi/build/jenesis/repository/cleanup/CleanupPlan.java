package build.jenesis.repository.cleanup;

import module java.base;

/**
 * The outcome of applying a {@link RetentionPolicy}: the versions to evict, each with the rule that condemned it.
 * A plan is computed before anything is deleted, so it can be previewed as a dry run and reported afterwards.
 */
public record CleanupPlan(List<Eviction> evictions) {

    /** A version marked for removal and the retention rule that condemned it. */
    public record Eviction(Release release, String reason) {
    }

    public boolean isEmpty() {
        return evictions.isEmpty();
    }
}
