package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.IntervalSetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cadence dial every maintenance provider reads its interval through. The load-bearing property is
 * {@link #a_malformed_dial_falls_back_instead_of_escaping_create()}: {@code resolve} must never throw, because
 * {@code MaintenanceTaskProvider.resolve} calls every discovered provider's {@code create} in one uncontained loop and
 * the application constructs the scheduler from it at boot - so an escaping parse failure does not disable one pass,
 * it drops every pass and fails startup.
 */
class IntervalSettingTest {

    private static final String HOUR_TEXT = "PT1H";

    private static final Duration HOUR = Duration.parse(HOUR_TEXT);

    private static final IntervalSetting CADENCE = IntervalSetting.of("cleanup-interval", HOUR_TEXT);

    private static final IntervalSetting MILLIS = IntervalSetting.millis("scan-interval-millis", HOUR_TEXT);

    private static UnaryOperator<String> config(String value) {
        return key -> "cleanup-interval".equals(key) || "scan-interval-millis".equals(key) ? value : null;
    }

    @Test
    void an_unset_dial_runs_at_the_product_default() {
        assertThat(CADENCE.resolve(_ -> null)).isEqualTo(HOUR);
        assertThat(CADENCE.resolve(config("   "))).isEqualTo(HOUR);
        assertThat(MILLIS.resolve(_ -> null)).isEqualTo(HOUR);
    }

    @Test
    void an_iso_8601_dial_is_honoured() {
        assertThat(CADENCE.resolve(config("PT10M"))).isEqualTo(Duration.ofMinutes(10));
        assertThat(CADENCE.resolve(config("P1D"))).isEqualTo(Duration.ofDays(1));
        assertThat(CADENCE.resolve(config("  PT6H  "))).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void the_suffixed_style_an_environment_variable_carries_is_honoured() {
        // JENREG_CLEANUP_INTERVAL=6h is what an operator writes; today every maintenance dial rejects it
        // (silently falling back in the guarded providers, aborting the resolve in the unguarded ones) while the free
        // core's proxy negative-cache window already accepts exactly this style - the parity gap this closes.
        assertThat(CADENCE.resolve(config("500ms"))).isEqualTo(Duration.ofMillis(500));
        assertThat(CADENCE.resolve(config("90s"))).isEqualTo(Duration.ofSeconds(90));
        assertThat(CADENCE.resolve(config("5m"))).isEqualTo(Duration.ofMinutes(5));
        assertThat(CADENCE.resolve(config("6h"))).isEqualTo(Duration.ofHours(6));
        assertThat(CADENCE.resolve(config("2D"))).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void a_bare_number_is_refused_on_a_cadence_dial_rather_than_given_a_guessed_unit() {
        // Spring's relaxed binding reads a bare number as milliseconds, the negative-cache window reads it
        // as seconds. A dial that picked one would be wrong by a factor of a thousand for half its operators.
        assertThat(CADENCE.resolve(config("3600"))).isEqualTo(HOUR);
    }

    @Test
    void a_millis_keyed_dial_reads_a_bare_number_as_milliseconds() {
        // Its unit is in the key, so there is nothing to guess.
        assertThat(MILLIS.resolve(config("600000"))).isEqualTo(Duration.ofMinutes(10));
        assertThat(MILLIS.resolve(config(" 1800000 "))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void a_millis_keyed_dial_still_honours_a_duration_an_operator_wrote_into_it() {
        assertThat(MILLIS.resolve(config("PT10M"))).isEqualTo(Duration.ofMinutes(10));
        assertThat(MILLIS.resolve(config("30m"))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void a_non_positive_cadence_falls_back_and_never_disables_the_pass() {
        // Disabling a pass has exactly two routes - jenreg.<task>=false and the provider's own enablement
        // setting - and a cadence dial does not become a third. A pass that is listed must actually run.
        for (String value : List.of("PT0S", "P0D", "0s", "-PT1H", "-5m")) {
            assertThat(CADENCE.resolve(config(value))).as("cadence %s", value).isEqualTo(HOUR);
        }
        for (String value : List.of("0", "-1", "PT0S")) {
            assertThat(MILLIS.resolve(config(value))).as("millis cadence %s", value).isEqualTo(HOUR);
        }
    }

    @Test
    void a_malformed_dial_falls_back_instead_of_escaping_create() {
        // The property the whole type exists for: one operator's typo must cost one pass its configured cadence, not
        // cost the deployment all its passes and its startup.
        for (String value : List.of("hourly", "1 hour", "PT", "P", "PTM", "6hh", "h6", "--5m", "5w", "1e3", "3,600",
                "PT1H; rm -rf /", "9223372036854775808", "٦h", "6 h", "PT1H PT2H", "null")) {
            assertThat(CADENCE.resolve(config(value))).as("cadence %s", value).isEqualTo(HOUR);
            assertThat(MILLIS.resolve(config(value))).as("millis cadence %s", value).isEqualTo(HOUR);
        }
    }

    @Test
    void a_failing_configuration_lookup_is_the_callers_failure_not_a_swallowed_one() {
        // resolve() guards the *parse*, not the configuration chain: a settings backend that is down is a real failure
        // the caller must see, not a cadence to shrug at.
        assertThatThrownBy(() -> CADENCE.resolve(_ -> {
            throw new IllegalStateException("settings backend down");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_key_and_default_are_what_the_settings_catalogue_renders() {
        // The catalogue entry is built from these, so the documented default and the effective one cannot drift -
        // today each cadence default is written twice, once per SettingsContributor and once per provider constant.
        assertThat(CADENCE.key()).isEqualTo("cleanup-interval");
        assertThat(CADENCE.fallback()).isEqualTo(HOUR);
        assertThat(CADENCE.fallback().toString()).isEqualTo("PT1H");
    }

    /** The one dial shape with a maximum: a cadence that is the bound on an exposure rather than a
     *  cost/freshness trade. Keyed off its own name so the log-once announcement cannot be confused with the
     *  fall-back dials above. */
    private static final IntervalSetting BOUNDED =
            IntervalSetting.of("index-rebase-interval", "P7D").atMost(Duration.ofDays(30));

    private static UnaryOperator<String> rebase(String value) {
        return key -> "index-rebase-interval".equals(key) ? value : null;
    }

    @Test
    void a_dial_with_a_ceiling_clamps_a_value_above_it_rather_than_falling_back() {
        // P3650D is exactly that defect: it parses cleanly, so nothing rejected it, and it removed the only repair
        // behind a lost withhold transition. The clamp keeps the operator's intent (as infrequent as possible) at the
        // least frequent value that still bounds the exposure - falling back to the weekly default would override an
        // intent that is legitimate right up to the maximum.
        assertThat(BOUNDED.resolve(rebase("P3650D"))).isEqualTo(Duration.ofDays(30));
        assertThat(BOUNDED.resolve(rebase("100d"))).isEqualTo(Duration.ofDays(30));
        assertThat(BOUNDED.resolve(rebase("PT9000H"))).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void a_value_at_or_below_the_ceiling_is_honoured_untouched() {
        assertThat(BOUNDED.resolve(rebase("P30D"))).as("the maximum itself is a legal setting")
                .isEqualTo(Duration.ofDays(30));
        assertThat(BOUNDED.resolve(rebase("P14D"))).isEqualTo(Duration.ofDays(14));
        assertThat(BOUNDED.resolve(rebase("PT6H"))).isEqualTo(Duration.ofHours(6));
        assertThat(BOUNDED.resolve(_ -> null)).as("and an unset bounded dial still runs at the product default")
                .isEqualTo(Duration.ofDays(7));
        assertThat(BOUNDED.resolve(rebase("nonsense"))).as("a malformed one still falls back, not to the ceiling")
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void a_ceiling_is_opt_in_and_an_ordinary_cadence_keeps_the_operators_decision() {
        // The negative control for the leg above, and the settled rule: a sweep an operator wants to run yearly
        // is a slow sweep, and slow is their call. Only a cadence whose extreme value removes a bound gets a maximum.
        assertThat(CADENCE.resolve(config("P3650D"))).isEqualTo(Duration.ofDays(3650));
        assertThat(CADENCE.ceiling()).isEmpty();
        assertThat(MILLIS.ceiling()).isEmpty();
    }

    @Test
    void the_ceiling_is_what_the_settings_catalogue_renders() {
        // Same reason as the key and default above: the bound an operator is held to and the bound the settings screen
        // tells them about are one value, so they cannot drift into a catalogue that documents a cap that is not there.
        assertThat(BOUNDED.ceiling()).contains(Duration.ofDays(30));
        assertThat(BOUNDED.ceiling().orElseThrow().toString()).isEqualTo("PT720H");
    }

    @Test
    void a_ceiling_below_the_product_default_fails_where_it_is_written() {
        // Every deployment would silently run clamped, at a cadence the provider never declared - a programming error,
        // not operator input, so it fails at the declaration rather than resolving to a surprise.
        assertThatThrownBy(() -> IntervalSetting.of("index-rebase-interval", "P7D")
                .atMost(Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index-rebase-interval");
    }

    @Test
    void a_provider_shipping_a_default_that_is_not_a_duration_fails_where_it_is_written() {
        // The default is a String so the settings reference can print it - a value built at run time is invisible
        // to the extractor and published every such row as "(computed)". Taking text buys that at the cost of a
        // literal that might not parse, so it parses at the declaration and throws naming the key, rather than
        // deferring to the first resolve() and falling back to a cadence nobody declared.
        assertThatThrownBy(() -> IntervalSetting.of("cleanup-interval", "every hour"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval")
                .hasMessageContaining("every hour");
        assertThatThrownBy(() -> IntervalSetting.of("cleanup-interval", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
    }

    @Test
    void the_default_is_readable_as_the_text_a_deployment_would_type() {
        // What the module's catalogue entry renders. It is the same characters the provider wrote, which is the
        // whole point: the reference prints a value an operator could paste back into the dial.
        assertThat(CADENCE.fallbackText()).isEqualTo("PT1H");
        assertThat(CADENCE.fallback()).isEqualTo(HOUR);
        assertThat(BOUNDED.fallbackText()).isEqualTo("P7D");
    }

    @Test
    void a_provider_shipping_an_unusable_default_fails_where_it_is_written() {
        // Not operator input: a zero default would resolve to a pass that spins on every worker iteration, so it is a
        // programming error and fails fast rather than degrading.
        assertThatThrownBy(() -> IntervalSetting.of("cleanup-interval", "PT0S"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> IntervalSetting.of("cleanup-interval", "-PT1H"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntervalSetting.millis("  ", HOUR_TEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
