package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.store.SettingsAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape badges and risk notices at the model level: the console reads a repository's parsed {@link
 * SettingsAdmin.RepositoryShape} straight from the one {@code Definition} the router routes on, so the list/detail
 * badges render the right shape for each of the generalized model's cells - hosted, caching proxy, {@code nocache}
 * pass-through, {@code harden}, a group, and the writable-plus-fallbacks host+proxy hybrid the old three-type model
 * could not name - and a valid-but-risky definition ({@code unscreened}, plaintext, mixed screening strength) surfaces
 * its warning as a non-blocking notice (not a refusal). Drives the deployment-wide {@link SettingsAdmin} over a real
 * store, no Spring context, the sibling of {@code SettingsAdminTest}.
 */
public class RepositoryShapeBadgeTest {

    @TempDir
    Path root;

    private SettingsAdmin settings;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        settings = new SettingsAdmin(store);
    }

    @Test
    void a_hosted_repository_is_writable_with_no_fallbacks() throws IOException {
        settings.setRepository("releases", "hosted");
        SettingsAdmin.RepositoryShape shape = settings.shape("releases");
        assertThat(shape.configured()).isTrue();
        assertThat(shape.writable()).as("hosted accepts uploads").isTrue();
        assertThat(shape.readOnly()).isFalse();
        assertThat(shape.hybrid()).isFalse();
        assertThat(shape.fallbacks()).isEmpty();
        assertThat(shape.warnings()).isEmpty();
    }

    @Test
    void a_caching_proxy_shows_a_stored_default_screened_upstream_fallback() throws IOException {
        settings.setRepository("central", "proxy https://repo1.maven.org/maven2");
        SettingsAdmin.RepositoryShape shape = settings.shape("central");
        assertThat(shape.writable()).as("a proxy is read-only").isFalse();
        assertThat(shape.readOnly()).isTrue();
        assertThat(shape.fallbacks()).singleElement().satisfies(fallback -> {
            assertThat(fallback.upstream()).isTrue();
            assertThat(fallback.source()).contains("repo1.maven.org");
            assertThat(fallback.store()).as("bare proxy caches its fetched bytes").isTrue();
            assertThat(fallback.screening()).isEqualTo("default");
        });
        assertThat(shape.warnings()).isEmpty();
    }

    @Test
    void a_nocache_proxy_shows_a_pass_through_upstream_fallback() throws IOException {
        settings.setRepository("lite", "proxy https://repo1.maven.org/maven2 nocache");
        SettingsAdmin.RepositoryShape shape = settings.shape("lite");
        assertThat(shape.fallbacks()).singleElement().satisfies(fallback -> {
            assertThat(fallback.store()).as("nocache is a pass-through").isFalse();
            assertThat(fallback.screening()).isEqualTo("default");
        });
    }

    @Test
    void a_harden_proxy_shows_a_hardened_upstream_fallback() throws IOException {
        settings.setRepository("hard", "proxy https://untrusted.example/repo harden");
        SettingsAdmin.RepositoryShape shape = settings.shape("hard");
        assertThat(shape.fallbacks()).singleElement().satisfies(fallback ->
                assertThat(fallback.screening()).isEqualTo("harden"));
    }

    @Test
    void a_group_shows_inner_repository_references_in_order() throws IOException {
        settings.setRepository("all", "group releases,central");
        SettingsAdmin.RepositoryShape shape = settings.shape("all");
        assertThat(shape.writable()).isFalse();
        assertThat(shape.fallbacks()).hasSize(2);
        assertThat(shape.fallbacks().get(0).upstream()).isFalse();
        assertThat(shape.fallbacks().get(0).repository()).isEqualTo("releases");
        assertThat(shape.fallbacks().get(1).repository()).isEqualTo("central");
    }

    @Test
    void a_writable_hybrid_shows_writable_and_its_fallbacks() throws IOException {
        settings.setRepository("frontdoor", "writable fallback releases fallback https://repo1.maven.org/maven2 harden");
        SettingsAdmin.RepositoryShape shape = settings.shape("frontdoor");
        assertThat(shape.writable()).as("the hybrid accepts uploads").isTrue();
        assertThat(shape.hybrid()).as("writable AND fallbacked is the host+proxy hybrid").isTrue();
        assertThat(shape.fallbacks()).hasSize(2);
        assertThat(shape.fallbacks().get(0).upstream()).as("an inner-repo view").isFalse();
        assertThat(shape.fallbacks().get(1).upstream()).isTrue();
        assertThat(shape.fallbacks().get(1).screening()).isEqualTo("harden");
    }

    @Test
    void a_mixed_strength_definition_surfaces_a_warning_but_is_not_refused() throws IOException {
        // A harden upstream beside a weaker (default) upstream: valid (ordering is operator expressiveness) but flagged
        // The definition is STORED (not refused) and its warning is surfaced.
        settings.setRepository("mixed",
                "fallback https://a.example/repo fallback https://b.example/repo harden");
        SettingsAdmin.RepositoryShape shape = settings.shape("mixed");
        assertThat(shape.configured()).as("the risky-but-valid definition was stored, not refused").isTrue();
        assertThat(shape.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("mixed screening strength"));
    }

    @Test
    void an_unscreened_definition_surfaces_a_warning_but_is_not_refused() throws IOException {
        settings.setRepository("open", "fallback https://a.example/repo unscreened");
        SettingsAdmin.RepositoryShape shape = settings.shape("open");
        assertThat(shape.fallbacks()).singleElement().satisfies(fallback ->
                assertThat(fallback.screening()).isEqualTo("unscreened"));
        assertThat(shape.warnings()).anySatisfy(warning -> assertThat(warning).contains("unscreened"));
    }

    @Test
    void a_plaintext_upstream_is_refused_and_only_warned_about_once_the_dial_admits_it() throws IOException {
        // The proxy upstream used to be the one operator-configured outbound target that was warned about
        // rather than refused, while the webhook, forward, emulator, redirect and import targets all decline one.
        // It is refused now, and the message names the hazard and the deliberate opt-out.
        assertThatThrownBy(() -> settings.setRepository("plain", "proxy http://a.example/repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not https")
                .hasMessageContaining("upstream credential")
                .hasMessageContaining("proxy-allow-internal");
        assertThat(settings.repositories()).as("nothing was stored").doesNotContainKey("plain");

        // With the dial taken - the deployment that really does pull from a plaintext internal mirror - the value is
        // storable again and the console notice is what remains: an accepted risk, still stated loudly (§9).
        settings.save("proxy-allow-internal", "true");
        settings.setRepository("plain", "proxy http://a.example/repo");
        SettingsAdmin.RepositoryShape shape = settings.shape("plain");
        assertThat(shape.warnings()).anySatisfy(warning -> assertThat(warning).contains("plaintext"));
    }

    @Test
    void an_unconfigured_repository_is_the_neutral_default_shape() throws IOException {
        SettingsAdmin.RepositoryShape shape = settings.shape("never-defined");
        assertThat(shape.configured()).isFalse();
        assertThat(shape.writable()).isTrue();
        assertThat(shape.fallbacks()).isEmpty();
        assertThat(shape.warnings()).isEmpty();
    }
}
