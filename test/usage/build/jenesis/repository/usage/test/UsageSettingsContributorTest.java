package build.jenesis.repository.usage.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The usage module's declared settings, discovered exactly as the settings catalogue lists them: the module declares
 * {@code provides SettingsContributor with UsageSettingsContributor}, so the contributor is reached through
 * {@link ServiceLoader#load}. It contributes exactly the {@code track-key-usage} dial - a BOOLEAN gate defaulting on,
 * in the Operations group, deployment-wide (not tenant-overridable) - so the tracker's setting is in the catalogue
 * exactly when an image carries the tracker.
 */
class UsageSettingsContributorTest {

    private static final String CONTRIBUTOR = "build.jenesis.repository.usage.UsageSettingsContributor";

    private static List<SettingsContributor> discovered() {
        return ServiceLoader.load(SettingsContributor.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    @Test
    void the_contributor_is_discovered_via_service_loader() {
        assertThat(discovered())
                .as("installing the usage module adds its settings through the discovered contributor")
                .anyMatch(contributor -> contributor.getClass().getName().equals(CONTRIBUTOR));
        assertThat(discovered().stream().flatMap(contributor -> contributor.settings().stream()))
                .anyMatch(setting -> setting.key().equals("track-key-usage"));
    }

    @Test
    void it_declares_exactly_the_track_key_usage_gate_setting() {
        SettingsContributor usage = discovered().stream()
                .filter(contributor -> contributor.getClass().getName().equals(CONTRIBUTOR))
                .findFirst().orElseThrow(() -> new AssertionError("UsageSettingsContributor was not discovered"));

        List<Setting> settings = usage.settings();
        assertThat(settings).as("the module contributes exactly its usage-tracking dial").hasSize(1);
        Setting trackKeyUsage = settings.getFirst();
        assertThat(trackKeyUsage.key()).isEqualTo("track-key-usage");
        assertThat(trackKeyUsage.group()).isEqualTo("Operations");
        assertThat(trackKeyUsage.kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(trackKeyUsage.defaultValue())
                .as("key-usage tracking is on unless switched off - it costs one write per credential per day")
                .isEqualTo("true");
        assertThat(trackKeyUsage.enablement())
                .as("track-key-usage is the module's enablement gate").isTrue();
        assertThat(trackKeyUsage.tenantOverridable())
                .as("the tracking dial is a deployment-wide knob, not a per-tenant override").isFalse();
    }
}
