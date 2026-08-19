package build.jenesis.repository.test;

import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.store.Features;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WFE.1 - the core {@link ImportEdgeProvider} discovery seam, unit half. Proves the single question the free
 * {@link build.jenesis.repository.server.RepositoryAutoConfiguration} asks - "is a distribution's import edge
 * installed?" - answers off the shared {@code Features} enable/disable convention: {@link TestImportEdgeProvider} is
 * discovered via {@link java.util.ServiceLoader} (this test module's {@code provides} clause) but stays inert until its
 * required-config activation key is set, so the free import edge is served by default and yields only when a
 * distribution is genuinely configured for. The end-to-end consequence - the free mapping is then not registered - is
 * proven against the live server by {@link ImportEdgeYieldTest}.
 */
class ImportEdgeProviderTest {

    @Test
    void no_provider_is_installed_by_default_so_the_free_import_edge_is_served() {
        // The default lookup (system properties / environment) with the activation key unset: the discovered test
        // provider self-disables on its missing required config, so no import edge is claimed.
        System.clearProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY));
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("a discovered-but-inert provider does not claim the import edge - the free edge is served")
                    .isFalse();
        } finally {
            Features.reset();
        }
    }

    @Test
    void a_provider_is_installed_once_its_required_config_is_set() {
        System.setProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY), "true");
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("an active provider claims the import edge, so the free controller yields")
                    .isTrue();
        } finally {
            System.clearProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY));
            Features.reset();
        }
    }

    @Test
    void there_is_nothing_to_select_so_no_selection_key_can_claim_or_release_the_edge() {
        // T-101b: unlike the named singletons beside it (fetcher, walk, gc, rate-limiter, token-exchange,
        // key-usage, tenants), this SPI is a pure presence signal resolved through Providers.installedNames - there
        // is no jenesis.repository.import-edge=<name> key, so the §9 "explicitly selected but unavailable" case
        // cannot arise and setting such a key changes nothing in either direction.
        System.setProperty("jenesis.repository.import-edge", "not-installed");
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("an inert provider still yields the free edge, selection key or not")
                    .isFalse();
            System.setProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY), "true");
            Features.reset();
            assertThat(ImportEdgeProvider.installed())
                    .as("an active provider still claims the edge, selection key or not")
                    .isTrue();
        } finally {
            System.clearProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY));
            System.clearProperty("jenesis.repository.import-edge");
            Features.reset();
        }
    }

    @Test
    void an_explicit_feature_off_switch_falls_back_to_the_free_import_edge() {
        // Even with the required config present, jenesis.repository.<name>=false disables the provider (the shared
        // Features switch), so a deployment can fall back to the free import edge without removing the module.
        System.setProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY), "true");
        System.setProperty("jenesis.repository.test-import-edge", "false");
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("an explicitly-disabled provider does not claim the edge")
                    .isFalse();
        } finally {
            System.clearProperty(Features.key(TestImportEdgeProvider.ACTIVATION_KEY));
            System.clearProperty("jenesis.repository.test-import-edge");
            Features.reset();
        }
    }
}
