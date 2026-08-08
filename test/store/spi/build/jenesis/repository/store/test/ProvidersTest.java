package build.jenesis.repository.store.test;

import build.jenesis.repository.store.Providers;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared provider-resolution primitives, driven over synthetic providers rather than {@link ServiceLoader} - the
 * helper deliberately takes the discovered providers as an argument (the {@code uses} clause stays with the SPI that
 * owns the service interface), so its policies are unit-testable without a module graph.
 *
 * <p>The suite is the acceptance matrix of the resolution contract: the absent outcome of every primitive, the
 * packaging errors ({@code null} provider, blank name, duplicate name, duplicate provider) that a discovery-order
 * winner would hide, additive contribution and its ordering, unique resolution with and without an explicit
 * selection, the ambiguity that replaces "the first in discovery order", the exclusive default with its
 * before-construction configuration validation, and - the point of the whole exercise (&sect;9) - that an
 * <em>explicitly selected</em> implementation which is absent, switched off or unconfigured throws naming the
 * selection and what is missing, while only an <em>unselected</em> optional capability degrades to its sentinel.
 */
class ProvidersTest {

    private static final String SPI = "widget";

    private static final Function<Fake, String> NAME = Fake::name;

    private static final Function<Fake, Optional<String>> CREATE = Fake::create;

    private static final Predicate<Fake> ENABLED = _ -> true;

    private static final Function<Fake, List<String>> CONFIGURED = _ -> List.of();

    // --- absent outcomes -------------------------------------------------------------------------------------

    @Test
    void an_empty_module_graph_yields_the_absent_outcome_or_a_named_failure() {
        List<Fake> none = List.of();
        assertThat(Providers.all(SPI, none, NAME, ENABLED, CREATE)).isEmpty();
        assertThat(Providers.optionalUnique(SPI, none, NAME, Optional.empty(), ENABLED, CREATE)).isEmpty();
        assertThat(Providers.installedNames(SPI, none, NAME, ENABLED)).isEmpty();
        // The two policies with no unselected outcome cannot degrade: they name what they could not resolve.
        assertThatThrownBy(() -> Providers.namedUnique(SPI, none, NAME, "alfa", CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alfa")
                .hasMessageContaining("no installed provider answers to it");
        assertThatThrownBy(() ->
                Providers.exclusiveWithDefault(SPI, none, NAME, Optional.empty(), "filesystem", CONFIGURED, Fake::name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem");
    }

    // --- packaging errors, rejected by every primitive -------------------------------------------------------

    @Test
    void two_providers_answering_to_one_name_are_a_packaging_error() {
        // Case-insensitively equal: one jenesis.repository.<name>=false switch would toggle both.
        List<Fake> clashing = List.of(alfa("dup", "a"), beta("DUP", "b"));
        for (Runnable primitive : everyPrimitive(clashing)) {
            assertThatThrownBy(primitive::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("answer to the name")
                    .hasMessageContaining("Alfa")
                    .hasMessageContaining("Beta")
                    .hasMessageContaining("never a silently chosen winner");
        }
    }

    @Test
    void one_provider_registered_twice_is_a_packaging_error() {
        List<Fake> twice = List.of(alfa("one", "a"), alfa("two", "b"));
        for (Runnable primitive : everyPrimitive(twice)) {
            assertThatThrownBy(primitive::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("registered more than once")
                    .hasMessageContaining("Alfa");
        }
    }

    @Test
    void a_provider_without_a_name_is_a_packaging_error() {
        for (String nameless : new String[] {null, "", "   "}) {
            List<Fake> anonymous = List.of(new Alfa(nameless, Optional.of("a")));
            for (Runnable primitive : everyPrimitive(anonymous)) {
                assertThatThrownBy(primitive::run)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("declares no name");
            }
        }
    }

    @Test
    void a_null_provider_is_a_packaging_error() {
        List<Fake> withNull = Arrays.asList(alfa("alfa", "a"), null);
        for (Runnable primitive : everyPrimitive(withNull)) {
            assertThatThrownBy(primitive::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is null");
        }
    }

    @Test
    void a_provider_returning_null_instead_of_an_optional_fails_loudly() {
        List<Fake> providers = List.of(alfa("alfa", "a"));
        Function<Fake, Optional<String>> nulled = _ -> null;
        assertThatThrownBy(() -> Providers.all(SPI, providers, NAME, ENABLED, nulled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null is never a legal SPI result");
        assertThatThrownBy(() ->
                Providers.optionalUnique(SPI, providers, NAME, Optional.empty(), ENABLED, nulled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null is never a legal SPI result");
        assertThatThrownBy(() -> Providers.namedUnique(SPI, providers, NAME, "alfa", nulled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null is never a legal SPI result");
        assertThatThrownBy(() -> Providers.exclusiveWithDefault(
                SPI, providers, NAME, Optional.empty(), "alfa", CONFIGURED, _ -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null is never a legal SPI result");
    }

    // --- ALL -------------------------------------------------------------------------------------------------

    @Test
    void every_enabled_implementation_contributes_in_a_deterministic_order() {
        List<Fake> discovered = List.of(gamma("zulu", "z"), beta("mike", "m"), alfa("alfa", "a"));
        assertThat(Providers.all(SPI, discovered, NAME, ENABLED, CREATE)).containsExactly("a", "m", "z");
        // The module path decides discovery order; the resolution must not.
        List<Fake> reversed = List.of(alfa("alfa", "a"), beta("mike", "m"), gamma("zulu", "z"));
        assertThat(Providers.all(SPI, reversed, NAME, ENABLED, CREATE))
                .isEqualTo(Providers.all(SPI, discovered, NAME, ENABLED, CREATE));
    }

    @Test
    void a_disabled_implementation_is_never_asked_to_create_anything() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("off", "b"));
        List<String> asked = new ArrayList<>();
        List<String> resolved = Providers.all(SPI, discovered, NAME,
                provider -> !provider.name().equals("off"),
                provider -> {
                    asked.add(provider.name());
                    return provider.create();
                });
        assertThat(resolved).containsExactly("a");
        assertThat(asked).containsExactly("alfa");
    }

    @Test
    void an_implementation_that_declines_contributes_nothing() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("quiet", null));
        assertThat(Providers.all(SPI, discovered, NAME, ENABLED, CREATE)).containsExactly("a");
    }

    // --- OPTIONAL_UNIQUE -------------------------------------------------------------------------------------

    @Test
    void an_uninstalled_or_switched_off_optional_capability_is_absent_rather_than_a_failure() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        assertThat(Providers.optionalUnique(SPI, discovered, NAME, Optional.empty(), _ -> false, CREATE)).isEmpty();
        assertThat(Providers.optionalUnique(SPI, List.of(), NAME, Optional.empty(), ENABLED, CREATE)).isEmpty();
    }

    @Test
    void the_single_enabled_implementation_resolves_without_a_selection() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        List<String> asked = new ArrayList<>();
        Optional<String> resolved = Providers.optionalUnique(SPI, discovered, NAME, Optional.empty(),
                provider -> provider.name().equals("bravo"),
                provider -> {
                    asked.add(provider.name());
                    return provider.create();
                });
        assertThat(resolved).contains("b");
        // Ambiguity is decided from the enablement policy, so no throw-away instance is ever built.
        assertThat(asked).containsExactly("bravo");
    }

    @Test
    void an_unselected_implementation_that_declines_yields_the_absent_outcome() {
        List<Fake> discovered = List.of(alfa("alfa", null));
        assertThat(Providers.optionalUnique(SPI, discovered, NAME, Optional.empty(), ENABLED, CREATE)).isEmpty();
    }

    @Test
    void two_enabled_implementations_without_a_selection_are_ambiguous_rather_than_a_discovery_order_winner() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        assertThatThrownBy(() ->
                Providers.optionalUnique(SPI, discovered, NAME, Optional.empty(), ENABLED, CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than one widget implementation is enabled")
                .hasMessageContaining("alfa")
                .hasMessageContaining("bravo")
                .hasMessageContaining("jenesis.repository.widget=<name>");
    }

    @Test
    void an_explicit_selection_outranks_the_enablement_toggle() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        List<String> consulted = new ArrayList<>();
        Optional<String> resolved = Providers.optionalUnique(SPI, discovered, NAME, Optional.of("bravo"),
                provider -> {
                    consulted.add(provider.name());
                    return false;
                }, CREATE);
        assertThat(resolved).contains("b");
        assertThat(consulted).isEmpty();
    }

    @Test
    void an_explicitly_selected_implementation_that_no_provider_answers_to_throws() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        assertThatThrownBy(() ->
                Providers.optionalUnique(SPI, discovered, NAME, Optional.of("charlie"), ENABLED, CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'charlie'")
                .hasMessageContaining("not on the module path, or the name is misspelled")
                .hasMessageContaining("refusing to degrade silently")
                .hasMessageContaining("[alfa, bravo]");
    }

    @Test
    void an_explicitly_selected_implementation_that_yields_nothing_throws_naming_the_config() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", null));
        assertThatThrownBy(() ->
                Providers.optionalUnique(SPI, discovered, NAME, Optional.of("bravo"), ENABLED, CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'bravo'")
                .hasMessageContaining("jenesis.repository.bravo=false")
                .hasMessageContaining("required configuration is unset")
                .hasMessageContaining("refusing to degrade silently");
    }

    @Test
    void a_selection_matches_a_provider_name_case_insensitively() {
        List<Fake> discovered = List.of(alfa("Alfa", "a"));
        assertThat(Providers.optionalUnique(SPI, discovered, NAME, Optional.of("ALFA"), _ -> false, CREATE))
                .contains("a");
    }

    @Test
    void a_blank_selection_means_unselected() {
        List<Fake> discovered = List.of(alfa("alfa", "a"));
        for (String blank : new String[] {"", "   "}) {
            assertThat(Providers.optionalUnique(SPI, discovered, NAME, Optional.of(blank), ENABLED, CREATE))
                    .contains("a");
        }
    }

    // --- NAMED_UNIQUE ----------------------------------------------------------------------------------------

    @Test
    void a_mandatory_selection_resolves_the_named_implementation() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        assertThat(Providers.namedUnique(SPI, discovered, NAME, " BRAVO ", CREATE)).isEqualTo("b");
    }

    @Test
    void a_mandatory_selection_may_not_be_absent() {
        List<Fake> discovered = List.of(alfa("alfa", "a"));
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> Providers.namedUnique(SPI, discovered, NAME, missing, CREATE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no unselected outcome");
        }
    }

    @Test
    void a_mandatory_selection_no_provider_answers_to_throws() {
        List<Fake> discovered = List.of(alfa("alfa", "a"));
        assertThatThrownBy(() -> Providers.namedUnique(SPI, discovered, NAME, "charlie", CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'charlie'")
                .hasMessageContaining("[alfa]");
    }

    @Test
    void a_mandatory_selection_whose_provider_yields_nothing_throws() {
        List<Fake> discovered = List.of(alfa("alfa", null));
        assertThatThrownBy(() -> Providers.namedUnique(SPI, discovered, NAME, "alfa", CREATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yielded no instance");
    }

    // --- EXCLUSIVE_WITH_DEFAULT ------------------------------------------------------------------------------

    @Test
    void an_unselected_deployment_gets_the_named_default() {
        List<Fake> discovered = List.of(alfa("filesystem", "fs"), beta("s3", "s3"));
        for (Optional<String> unselected : List.of(Optional.<String>empty(), Optional.of(""), Optional.of("  "))) {
            String resolved = Providers.exclusiveWithDefault(
                    SPI, discovered, NAME, unselected, "filesystem", CONFIGURED, provider -> provider.create().get());
            assertThat(resolved).isEqualTo("fs");
        }
    }

    @Test
    void an_explicit_selection_wins_over_the_default() {
        List<Fake> discovered = List.of(alfa("filesystem", "fs"), beta("s3", "s3"));
        String resolved = Providers.exclusiveWithDefault(SPI, discovered, NAME, Optional.of("S3"), "filesystem",
                CONFIGURED, provider -> provider.create().get());
        assertThat(resolved).isEqualTo("s3");
    }

    @Test
    void a_selected_backend_no_provider_answers_to_refuses_to_fall_back_to_the_default() {
        // The §9 exemplar: store=s3 with the s3 module absent must not boot against the local filesystem.
        List<Fake> discovered = List.of(alfa("filesystem", "fs"));
        assertThatThrownBy(() -> Providers.exclusiveWithDefault(SPI, discovered, NAME, Optional.of("s3"),
                "filesystem", CONFIGURED, provider -> provider.create().get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'s3'")
                .hasMessageContaining("refusing to fall back to the 'filesystem' default")
                .hasMessageContaining("[filesystem]");
    }

    @Test
    void an_absent_default_provider_throws_whether_or_not_it_was_named() {
        List<Fake> discovered = List.of(beta("s3", "s3"));
        for (Optional<String> selection : List.of(Optional.<String>empty(), Optional.of("filesystem"))) {
            assertThatThrownBy(() -> Providers.exclusiveWithDefault(SPI, discovered, NAME, selection,
                    "filesystem", CONFIGURED, provider -> provider.create().get()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No widget provider answers to the 'filesystem' default")
                    .hasMessageContaining("[s3]");
        }
    }

    @Test
    void a_backend_missing_required_configuration_fails_loudly_naming_every_missing_key() {
        List<Fake> discovered = List.of(alfa("filesystem", "fs"), beta("s3", "s3"));
        List<String> built = new ArrayList<>();
        assertThatThrownBy(() -> Providers.exclusiveWithDefault(SPI, discovered, NAME, Optional.of("s3"),
                "filesystem",
                provider -> provider.name().equals("s3") ? List.of("bucket", "region") : List.of(),
                provider -> {
                    built.add(provider.name());
                    return provider.create().get();
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'s3'")
                .hasMessageContaining("required configuration is missing: bucket, region");
        // Validated before construction: a misconfigured backend is never built, and never falls back either.
        assertThat(built).isEmpty();
    }

    @Test
    void the_unselected_default_is_configuration_checked_too() {
        List<Fake> discovered = List.of(alfa("filesystem", "fs"));
        assertThatThrownBy(() -> Providers.exclusiveWithDefault(SPI, discovered, NAME, Optional.empty(),
                "filesystem", _ -> List.of("root"), provider -> provider.create().get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'filesystem'")
                .hasMessageContaining("required configuration is missing: root");
    }

    // --- installed names -------------------------------------------------------------------------------------

    @Test
    void installed_names_report_every_implementation_in_a_stable_order() {
        List<Fake> discovered = List.of(gamma("zulu", "z"), beta("mike", null), alfa("alfa", "a"));
        assertThat(Providers.installedNames(SPI, discovered, NAME, ENABLED))
                .containsExactly("alfa", "mike", "zulu");
    }

    @Test
    void installed_names_can_report_only_the_usable_implementations() {
        List<Fake> discovered = List.of(alfa("alfa", "a"), beta("bravo", "b"));
        assertThat(Providers.installedNames(SPI, discovered, NAME, provider -> provider.name().equals("bravo")))
                .containsExactly("bravo");
        assertThat(Providers.installedNames(SPI, discovered, NAME, _ -> false)).isEmpty();
    }

    @Test
    void installed_names_are_handed_out_unmodifiable() {
        SortedSet<String> names = Providers.installedNames(SPI, List.of(alfa("alfa", "a")), NAME, ENABLED);
        assertThatThrownBy(() -> names.add("bravo")).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- fixtures --------------------------------------------------------------------------------------------

    /** Every primitive over the same providers - the packaging checks are shared by all five policies. */
    private static List<Runnable> everyPrimitive(List<Fake> discovered) {
        return List.of(
                () -> Providers.all(SPI, discovered, NAME, ENABLED, CREATE),
                () -> Providers.optionalUnique(SPI, discovered, NAME, Optional.empty(), ENABLED, CREATE),
                () -> Providers.namedUnique(SPI, discovered, NAME, "alfa", CREATE),
                () -> Providers.exclusiveWithDefault(
                        SPI, discovered, NAME, Optional.empty(), "alfa", CONFIGURED, Fake::name),
                () -> Providers.installedNames(SPI, discovered, NAME, ENABLED));
    }

    private static Fake alfa(String name, String product) {
        return new Alfa(name, Optional.ofNullable(product));
    }

    private static Fake beta(String name, String product) {
        return new Beta(name, Optional.ofNullable(product));
    }

    private static Fake gamma(String name, String product) {
        return new Gamma(name, Optional.ofNullable(product));
    }

    /** A synthetic provider: a name and the implementation it builds, empty when it declines. */
    private interface Fake {

        String name();

        Optional<String> create();
    }

    /** Three distinct provider <em>classes</em>, so a duplicate name and a duplicate provider are distinguishable. */
    private record Alfa(String name, Optional<String> create) implements Fake {
    }

    private record Beta(String name, Optional<String> create) implements Fake {
    }

    private record Gamma(String name, Optional<String> create) implements Fake {
    }
}
