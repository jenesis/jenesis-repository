package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.server.spi.TokenExchange;
import build.jenesis.repository.server.spi.TokenExchangeProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The config-driven SPI enable/disable convention against the real installed modules: a format configured off
 * ({@code jenreg.<name>=false}) degrades exactly like a missing module, and a singleton SPI's provider is
 * skippable by its feature toggle and selectable by the SPI's selection key
 * ({@code jenreg.token-exchange=<name>}) - so one image carries every module and configuration decides
 * what runs.
 *
 * <p>Since the three {@code server-spi} singletons resolve through the shared
 * {@code Providers.optionalUnique} primitive, and this suite pins the semantics that changed: a selection naming an
 * <em>uninstalled</em> implementation used to resolve to the SPI's {@code NONE} sentinel, so a deployment that asked
 * for rate limiting, workload-identity exchange or key-usage tracking and misspelled it - or forgot its module - came
 * up unlimited, unauthenticated-by-that-route and untracked while looking configured. It now throws at resolution
 * naming the selection and what is installed (&sect;9). Only <em>unselected</em> absence still degrades to
 * {@code NONE}.
 */
class FeatureTogglesTest {

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void a_format_configured_off_degrades_like_a_missing_module() {
        assertThat(RepositoryFormat.installed("maven")).isPresent();
        Features.configure(Map.of("jenreg.maven", "false")::get);
        assertThat(RepositoryFormat.installed("maven")).isEmpty();
        assertThat(RepositoryFormat.installed("raw")).isPresent();
    }

    @Test
    void a_token_exchange_configured_off_resolves_to_none() {
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isNotSameAs(TokenExchange.NONE);
        Features.configure(Map.of("jenreg.oidc", "false")::get);
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isSameAs(TokenExchange.NONE);
    }

    @Test
    void an_exclusive_selection_picks_its_implementation_by_name() {
        Features.configure(Map.of("jenreg.token-exchange", "oidc")::get);
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isNotSameAs(TokenExchange.NONE);
    }

    @Test
    void a_token_exchange_selection_naming_an_uninstalled_implementation_fails_loudly() {
        // (§9): this used to answer TokenExchange.NONE, so a deployment that configured workload identity and
        // misspelled the protocol booted with the exchange endpoint reporting "not installed" - and every CI job
        // silently falling back to a long-lived static credential.
        Features.configure(Map.of("jenreg.token-exchange", "not-installed")::get);
        assertThatThrownBy(() -> TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'not-installed'")
                .hasMessageContaining("jenreg.token-exchange=not-installed")
                .hasMessageContaining("no installed provider answers to it")
                .hasMessageContaining("refusing to degrade silently")
                .hasMessageContaining("[oidc]");
    }

    @Test
    void a_rate_limiter_configured_off_resolves_to_none() {
        assertThat(RateLimiterProvider.resolve(key -> null)).isNotSameAs(RateLimiter.NONE);
        Features.configure(Map.of("jenreg.token-bucket", "false")::get);
        assertThat(RateLimiterProvider.resolve(key -> null)).isSameAs(RateLimiter.NONE);
    }

    @Test
    void a_rate_limiter_selection_naming_an_uninstalled_implementation_fails_loudly() {
        // (§9): unlimited-while-configured is the worst possible degradation for a metering capability.
        Features.configure(Map.of("jenreg.rate-limiter", "coordinated")::get);
        assertThatThrownBy(() -> RateLimiterProvider.resolve(key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'coordinated'")
                .hasMessageContaining("jenreg.rate-limiter=coordinated")
                .hasMessageContaining("refusing to degrade silently")
                .hasMessageContaining("[token-bucket]");
    }

    @Test
    void a_key_usage_tracker_configured_off_resolves_to_none() {
        assertThat(KeyUsageTrackerProvider.resolve(Authorization.anonymous(), key -> null))
                .isNotSameAs(KeyUsageTracker.NONE);
        Features.configure(Map.of("jenreg.batching", "false")::get);
        assertThat(KeyUsageTrackerProvider.resolve(Authorization.anonymous(), key -> null))
                .isSameAs(KeyUsageTracker.NONE);
    }

    @Test
    void a_key_usage_selection_naming_an_uninstalled_implementation_fails_loudly() {
        // (§9): silently answering NONE would leave every credential reading "last used: never", which an
        // operator takes as evidence that an unused key is safe to revoke.
        Features.configure(Map.of("jenreg.key-usage", "streaming")::get);
        assertThatThrownBy(() -> KeyUsageTrackerProvider.resolve(Authorization.anonymous(), key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'streaming'")
                .hasMessageContaining("jenreg.key-usage=streaming")
                .hasMessageContaining("refusing to degrade silently")
                .hasMessageContaining("[batching]");
    }

    @Test
    void an_import_skips_a_format_configured_off(@TempDir Path root) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ImportSource source = (consumer, checkpoint) -> {
            consumer.accept("raw", "files/a.txt",
                    () -> new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
            checkpoint.reached(null);
        };
        Features.configure(Map.of("jenreg.raw", "false")::get);
        RepositoryImport.Result withheld = new RepositoryImport().run(source, store);
        assertThat(withheld.imported()).isZero();
        assertThat(withheld.skippedFormats()).containsExactly("raw");
        Features.reset();
        assertThat(new RepositoryImport().run(source, store).imported()).isEqualTo(1);
    }
}
