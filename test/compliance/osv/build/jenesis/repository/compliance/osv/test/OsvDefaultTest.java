package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.osv.OsvAdvisorySourceProvider;
import build.jenesis.repository.compliance.osv.OsvSettingsContributor;
import build.jenesis.repository.settings.Setting;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a deployment that configured nothing asks OSV, in the two places that decide it.
 *
 * <p>The answer shipped is no: a lookup is an outbound call to a public API, and nothing in this product reaches a
 * third party because somebody installed it. Every other suite that exercises the feed switches it on, so this is the
 * one that holds the shipped value - and it asks the provider for a source with nothing set rather than reading a
 * constant, which is the leg that would fail if the catalogue and the code were ever moved apart.
 */
class OsvDefaultTest {

    @Test
    void the_catalogue_shows_the_feed_off() {
        Setting declared = new OsvSettingsContributor().settings().stream()
                .filter(setting -> "osv".equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("osv is not in the OSV setting catalogue"));
        assertThat(declared.defaultValue()).as("what the settings screen and the generated reference show")
                .isEqualTo("false");
    }

    @Test
    void a_deployment_that_set_nothing_installs_no_osv_source() {
        assertThat(new OsvAdvisorySourceProvider().create(SignalContext.of("osv", _ -> null)))
                .as("no dial set anywhere: no source, so no outbound call").isEmpty();
    }

    @Test
    void naming_the_dial_installs_it() {
        // The other half: off by default must not mean unreachable.
        assertThat(new OsvAdvisorySourceProvider().create(
                SignalContext.of("osv", Map.of("osv", "true")::get))).isPresent();
    }
}
