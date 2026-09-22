package build.jenesis.repository.downloads.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.downloads.DownloadSettingsContributor;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownloadSettingsContributor} as the module's declared settings: it contributes exactly the
 * {@code track-downloads} dial - a BOOLEAN gate defaulting off - so the setting surfaces on the console exactly when
 * the downloads module is installed, and every read key stays on the configuration-principle rails (declared by a
 * contributor, never a stranded constant). The contributor is also discovered as a {@link SettingsContributor} via
 * {@link ServiceLoader}, the path installing the module lists its settings through.
 */
class DownloadSettingsContributorTest {

    @Test
    void it_declares_the_track_downloads_gate_setting() {
        List<Setting> settings = new DownloadSettingsContributor().settings();

        assertThat(settings).as("the module contributes its gate and its flush interval").hasSize(2);
        Setting flush = settings.get(1);
        assertThat(flush.key()).isEqualTo("download-flush-interval");
        assertThat(flush.kind()).isEqualTo(Setting.Kind.DURATION);
        assertThat(flush.defaultValue()).isEqualTo("PT6H");
        assertThat(flush.parses("PT1H")).isTrue();
        Setting trackDownloads = settings.getFirst();
        assertThat(trackDownloads.key()).isEqualTo("track-downloads");
        assertThat(trackDownloads.group()).isEqualTo("Operations");
        assertThat(trackDownloads.kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(trackDownloads.defaultValue())
                .as("tracking is on unless switched off - it costs one write per coordinate per interval")
                .isEqualTo("true");
        assertThat(trackDownloads.parses("false")).isTrue();
        assertThat(trackDownloads.enablement())
                .as("track-downloads is the module's enablement gate, paired with its enable/disable toggle").isTrue();
        assertThat(trackDownloads.tenantOverridable())
                .as("the tracking dial is a deployment-wide knob, not a per-tenant override").isFalse();
    }

    @Test
    void the_contributor_is_discovered_via_service_loader() {
        List<SettingsContributor> contributors = ServiceLoader.load(SettingsContributor.class)
                .stream().map(ServiceLoader.Provider::get).toList();

        assertThat(contributors)
                .as("installing the downloads module adds its settings through the discovered contributor")
                .hasAtLeastOneElementOfType(DownloadSettingsContributor.class);
        assertThat(contributors.stream().flatMap(contributor -> contributor.settings().stream()))
                .anyMatch(setting -> setting.key().equals("track-downloads"));
    }
}
