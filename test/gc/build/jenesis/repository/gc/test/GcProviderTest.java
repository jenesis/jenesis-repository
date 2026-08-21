package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.store.Features;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ServiceLoader resolution and its no-op default: the {@code mark-sweep} implementation answers when installed
 * and enabled; a disabled feature or an absent walk resolves to empty - and empty means <em>nothing is ever
 * reclaimed</em>, the SPI's default for the one unrecoverable operation.
 *
 * <p>Since the seam resolves through the shared {@code Providers.optionalUnique} primitive, so the empty
 * outcome is reserved for genuine <em>unselected</em> absence: an operator who explicitly names a collector - or a
 * walk for the collector to ride - that nothing answers to gets a loud failure instead, because a silent no-op would
 * read as a healthy idle system while storage grows without bound (&sect;9).
 */
class GcProviderTest {

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void the_provider_resolves_the_mark_sweep_collector_without_a_selection() {
        assertThat(GarbageCollectorProvider.installed()).isTrue();
        assertThat(GarbageCollectorProvider.resolve(key -> null)).isPresent();
    }

    @Test
    void an_explicitly_selected_collector_no_provider_answers_to_fails_loudly() {
        // (§9): this used to resolve to the no-op default, so `jenreg.gc=other` looked like a
        // deployment with garbage collection configured while nothing was ever reclaimed.
        Features.configure(key -> "jenreg.gc".equals(key) ? "other" : null);
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'other'")
                .hasMessageContaining("no installed provider answers to it")
                .hasMessageContaining("refusing to degrade silently")
                .hasMessageContaining("[mark-sweep]");
    }

    @Test
    void an_explicit_selection_of_the_installed_collector_outranks_its_toggle() {
        Features.configure(key -> switch (key) {
            case "jenreg.gc" -> "mark-sweep";
            case "jenreg.mark-sweep" -> "false";
            case null, default -> null;
        });
        assertThat(GarbageCollectorProvider.resolve(key -> null))
                .as("naming a switched-off collector is contradictory config; the selection wins, loudly or not at all")
                .isPresent();
    }

    @Test
    void a_disabled_feature_resolves_to_the_no_op_default() {
        Features.configure(key -> "jenreg.mark-sweep".equals(key) ? "false" : null);
        assertThat(GarbageCollectorProvider.resolve(key -> null)).isEmpty();
    }

    @Test
    void without_a_walk_nothing_ever_collects() {
        // The collector rides the shared walk; with the walk implementation switched off there is no enumeration to
        // ride, and the deployment degrades to no garbage collection rather than a hand-rolled listing loop. The
        // reference walk's feature name is `paged-descent` (StoreWalkProvider), so that is the toggle that removes
        // it - it used to be `store`, which is the artifact store's own selection key, so this line was configuring
        // two things at once and the operator following it would not have booted.
        Features.configure(key -> "jenreg.paged-descent".equals(key) ? "false" : null);
        assertThat(GarbageCollectorProvider.resolve(key -> null)).isEmpty();
    }

    @Test
    void an_explicitly_selected_walk_the_collector_cannot_ride_fails_loudly() {
        // The collector's own resolution is fine; the walk underneath it is the §9 miss, and the failure propagates
        // rather than being folded into "no collector installed".
        Features.configure(key -> "jenreg.walk".equals(key) ? "absent" : null);
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'absent'")
                .hasMessageContaining("no installed provider answers to it");
    }

    @Test
    void garbage_settings_fail_loudly_instead_of_collecting_with_a_wrong_stride() {
        assertThat(GarbageCollectorProvider.resolve(
                key -> "gc.stride".equals(key) ? "512" : null)).isPresent();
        // A malformed setting is the provider's own IllegalArgumentException, propagated unchanged by the resolution
        // primitive rather than absorbed into "this provider declined".
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(
                key -> "gc.stride".equals(key) ? "many" : null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(
                key -> "gc.stride".equals(key) ? "0" : null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
