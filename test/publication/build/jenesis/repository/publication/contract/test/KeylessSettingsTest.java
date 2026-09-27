package build.jenesis.repository.publication.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A composition that verifies no Sigstore bundle - the free core's, which this module is - lists no keyless dial. The
 * signature module declares them, and without a keyless scheme they configured nothing while the settings screen and
 * the reference described them as though the deployment could verify a bundle.
 */
class KeylessSettingsTest {

    @Test
    void without_a_keyless_scheme_the_keyless_dials_are_not_listed() {
        Set<String> keys = new HashSet<>();
        SettingsContributor.all().stream().map(Setting::key).forEach(keys::add);

        assertThat(keys).as("the signature module is installed").contains("signature-trusted-keys");
        assertThat(keys).as("and lists no dial a keyless scheme would read")
                .doesNotContain("signature-sigstore-trusted-root", "signature-sigstore-trusted-root-url",
                        "signature-sigstore-trusted-root-interval", "signature-provenance-accept");
    }
}
