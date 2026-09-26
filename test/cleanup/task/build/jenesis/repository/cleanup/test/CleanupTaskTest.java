package build.jenesis.repository.cleanup.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.task.CleanupTaskProvider;
import build.jenesis.repository.cleanup.task.RepositoryCleaner;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cleanup task: the cadence its provider parses, the engine selection it resolves through
 * {@code RetentionProvider} - including the fail-fast on a name no installed engine answers to - and the sweep
 * itself, which must group an interleaved inventory before judging it and then evict exactly what it planned.
 * What a policy <em>decides</em> is asserted against the contract alone, one module over.
 */
class CleanupTaskTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    @Test
    void a_malformed_cleanup_interval_falls_back_to_the_default_instead_of_aborting_the_resolve() {
        // The provider's create() runs inside the maintenance-task resolve; a DateTimeParseException thrown there
        // (an env var or property bypasses the settings write path's DURATION validation) would drop every pass.
        Map<String, String> config = Map.of("scheduled-cleanup", "true", "cleanup-interval", "hourly");

        assertThat(new CleanupTaskProvider().create(config::get).orElseThrow().interval())
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void an_explicitly_selected_retention_engine_that_no_module_answers_fails_fast() {
        // §9: an operator who names a retention engine (jenreg.retention=<name>) has chosen
        // it; a name no installed engine answers to must stop the start, not degrade silently to no-retention -
        // which would leave the cleanup endpoints answering 501 while artifacts the operator meant to age out are
        // held forever with nothing said. The message names the unsatisfiable selection so an operator can fix it.
        Map<String, String> config = Map.of("retention", "does-not-exist");
        assertThatThrownBy(() -> RetentionProvider.resolve(config::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("retention");
    }

    @Test
    void an_unselected_or_matching_selection_still_resolves_the_installed_engine() {
        // The fail-fast is scoped to an UNMATCHED explicit selection: an unset selection resolves the single enabled
        // engine, and a selection naming an installed engine resolves it - neither throws, so the §9 guard never
        // fires on a satisfiable configuration. (This module installs the "cleaner" engine.) The
        // unselected leg is "the single enabled engine", not "the first in discovery order": two enabled engines
        // with no selection are ambiguous and throw rather than letting the module path decide.
        assertThat(RetentionProvider.resolve(key -> null)).isPresent();
        assertThat(RetentionProvider.resolve(key -> "retention".equals(key) ? "cleaner" : null)).isPresent();
    }

    @Test
    void the_streaming_enumeration_groups_an_interleaved_inventory_before_judging() throws IOException {
        // The SPI's default releases(visitor) regroups a list-backed inventory whatever order its list is in, so
        // the engine's one-coordinate-at-a-time planner judges whole groups - an interleaved list must sweep
        // exactly like a grouped one (production's walk-ordered stream is grouped by construction).
        ListInventory inventory = new ListInventory(new ArrayList<>(List.of(
                new Release("org.a:lib", "1.1", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.b:lib", "9.0", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.a:lib", "1.0", false, NOW.minus(Duration.ofDays(2))),
                new Release("org.b:lib", "8.0", false, NOW.minus(Duration.ofDays(2))))));
        CleanupPlan plan = new RepositoryCleaner(new RetentionPolicy(1)).sweep(inventory, NOW);
        assertThat(plan.evictions()).extracting(e -> e.release().coordinate() + ":" + e.release().version())
                .containsExactlyInAnyOrder("org.a:lib:1.0", "org.b:lib:8.0");
        assertThat(inventory.remaining).extracting(Release::version).containsExactlyInAnyOrder("1.1", "9.0");
    }

    @Test
    void a_sweep_applies_exactly_the_planned_evictions() throws IOException {
        ListInventory inventory = new ListInventory(new ArrayList<>(List.of(
                new Release("org.example:lib", "1.2", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "1.1", false, NOW.minus(Duration.ofDays(2))),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(3))))));
        CleanupPlan plan = new RepositoryCleaner(new RetentionPolicy(1)).sweep(inventory, NOW);
        assertThat(plan.evictions()).hasSize(2);
        assertThat(inventory.remaining).extracting(Release::version).containsExactly("1.2");
    }

    private static final class ListInventory implements RepositoryInventory {

        private final List<Release> remaining;

        private ListInventory(List<Release> remaining) {
            this.remaining = remaining;
        }

        @Override
        public Collection<Release> releases() {
            return new ArrayList<>(remaining);
        }

        @Override
        public void evict(Release release) {
            remaining.remove(release);
        }
    }
}
