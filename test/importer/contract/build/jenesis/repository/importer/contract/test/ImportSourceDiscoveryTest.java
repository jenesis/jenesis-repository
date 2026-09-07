package build.jenesis.repository.importer.contract.test;

import module java.base;

import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The import sources are discovered once, in the SPI's home, and a connector configured off is unreachable by
 *  name on every edge alike - the console's import job used to discover the connectors raw and run one the API's
 *  edge had refused. */
class ImportSourceDiscoveryTest {

    @AfterEach
    void reset() {
        Features.reset();
    }

    @Test
    void a_connector_configured_off_is_declared_and_not_installed() {
        assertThat(ImportSourceProvider.declared()).extracting(ImportSourceProvider::name).contains("nexus", "artifactory", "maven");
        assertThat(ImportSourceProvider.installed("nexus", key -> null)).isPresent();

        Features.configure(key -> "jenreg.nexus".equals(key) ? "false" : null);

        assertThat(ImportSourceProvider.declared()).extracting(ImportSourceProvider::name).contains("nexus");
        assertThat(ImportSourceProvider.installed()).extracting(ImportSourceProvider::name).doesNotContain("nexus").contains("artifactory");
        assertThat(ImportSourceProvider.installed("nexus", Features.settings())).isEmpty();
        assertThat(ImportSourceProvider.installed("no-such-connector", key -> null)).isEmpty();
    }
}
