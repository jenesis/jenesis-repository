package build.jenesis.repository.format.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Features;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The optional-unique fetcher SPI seam {@link FetcherProvider#resolve}, driven against three
 * {@link java.util.ServiceLoader} stub providers this module registers ({@code empty}, which always declines,
 * {@code alpha} answering {@code 201} and {@code beta} answering {@code 202}).
 *
 * <p>Since the seam resolves through the shared {@code Providers.optionalUnique} primitive, and this suite
 * pins the two semantics that changed with it:
 *
 * <ul>
 * <li>"the first enabled provider in discovery order" is <b>no longer a policy</b> - with more than one enabled
 *     fetcher and nothing selected, resolution fails naming the candidates instead of letting the module path decide
 *     which transport a deployment proxies through;</li>
 * <li>an <em>explicitly selected</em> {@code jenreg.fetcher=<name>} that no provider answers to, or whose
 *     provider declines, <b>throws</b> instead of silently answering {@link ProxyFormat.Fetcher#NONE} - the §9
 *     silent-fallback defect. Only an <em>unselected</em> deployment with nothing enabled gets the sentinel.</li>
 * </ul>
 */
class FetcherProviderTest {

    @AfterEach
    void resetFeatures() {
        Features.reset();
    }

    /** A config lookup over a fixed map, so a test controls the {@code jenreg.*} feature toggles the
     *  resolver reads without touching real system properties or the environment. */
    private static UnaryOperator<String> config(Map<String, String> values) {
        return values::get;
    }

    /** Install {@code values} as the shared feature lookup and hand the same lookup back as the resolver's config. */
    private static UnaryOperator<String> configured(Map<String, String> values) {
        UnaryOperator<String> config = config(values);
        Features.configure(config);
        return config;
    }

    private static int status(ProxyFormat.Fetcher fetcher) throws IOException {
        return fetcher.fetch(URI.create("https://upstream.example/x"), Map.of()).orElseThrow().status();
    }

    @Test
    void an_explicit_selection_picks_that_provider_by_name() throws IOException {
        assertThat(status(FetcherProvider.resolve(configured(Map.of("jenreg.fetcher", "beta")))))
                .as("beta is selected by name").isEqualTo(202);
        assertThat(status(FetcherProvider.resolve(configured(Map.of("jenreg.fetcher", "alpha")))))
                .as("alpha is selected by name").isEqualTo(201);
    }

    @Test
    void an_explicit_selection_outranks_the_feature_toggle() {
        // Naming an implementation that is also switched off is contradictory configuration; the selection wins
        // rather than quietly resolving to a different transport.
        UnaryOperator<String> contradictory = configured(Map.of(
                "jenreg.fetcher", "beta",
                "jenreg.beta", "false"));
        assertThat(FetcherProvider.resolve(contradictory)).isNotSameAs(ProxyFormat.Fetcher.NONE);
    }

    @Test
    void the_single_enabled_fetcher_resolves_without_a_selection() throws IOException {
        UnaryOperator<String> onlyAlpha = configured(Map.of(
                "jenreg.empty", "false",
                "jenreg.beta", "false"));
        assertThat(status(FetcherProvider.resolve(onlyAlpha)))
                .as("exactly one enabled fetcher needs no selection").isEqualTo(201);
    }

    @Test
    void two_enabled_fetchers_without_a_selection_are_ambiguous_rather_than_a_discovery_order_winner() {
        // this used to answer alpha, purely because the module path happened to list it before beta. Which
        // transport a deployment proxies through is a configuration decision, never a packaging accident.
        UnaryOperator<String> ambiguous = configured(Map.of("jenreg.empty", "false"));
        assertThatThrownBy(() -> FetcherProvider.resolve(ambiguous))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than one fetcher implementation is enabled")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
                .hasMessageContaining("jenreg.fetcher=<name>");
    }

    @Test
    void an_unselected_fetcher_that_declines_yields_the_none_sentinel() {
        // The lone enabled provider builds nothing (its own configuration switches it off in a way the toggle does
        // not see): unselected, that is a legitimate absence and degrades to NONE.
        UnaryOperator<String> onlyDeclining = configured(Map.of(
                "jenreg.alpha", "false",
                "jenreg.beta", "false"));
        assertThat(FetcherProvider.resolve(onlyDeclining)).isSameAs(ProxyFormat.Fetcher.NONE);
    }

    @Test
    void nothing_enabled_resolves_to_the_none_fetcher() {
        UnaryOperator<String> allOff = configured(Map.of(
                "jenreg.empty", "false",
                "jenreg.alpha", "false",
                "jenreg.beta", "false"));
        assertThat(FetcherProvider.resolve(allOff))
                .as("with every provider disabled the resolver answers the NONE singleton by identity")
                .isSameAs(ProxyFormat.Fetcher.NONE);
    }

    @Test
    void an_explicitly_selected_fetcher_no_provider_answers_to_fails_loudly() {
        // (§9): this used to answer NONE, so a deployment that misspelled its transport - or forgot its
        // module - served every proxy route as a 404 that looks exactly like "upstream does not have it".
        UnaryOperator<String> misspelled = configured(Map.of("jenreg.fetcher", "htpp"));
        assertThatThrownBy(() -> FetcherProvider.resolve(misspelled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'htpp'")
                .hasMessageContaining("no installed provider answers to it")
                .hasMessageContaining("refusing to degrade silently")
                .as("the diagnostic lists what is installed so the misspelling is obvious")
                .hasMessageContaining("[alpha, beta, empty]");
    }

    @Test
    void an_explicitly_selected_fetcher_that_declines_fails_loudly() {
        UnaryOperator<String> declining = configured(Map.of("jenreg.fetcher", "empty"));
        assertThatThrownBy(() -> FetcherProvider.resolve(declining))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'empty'")
                .hasMessageContaining("yielded no instance")
                .hasMessageContaining("required configuration is unset")
                .hasMessageContaining("refusing to degrade silently");
    }
}
