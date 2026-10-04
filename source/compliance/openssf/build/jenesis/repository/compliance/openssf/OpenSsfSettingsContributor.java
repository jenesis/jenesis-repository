package build.jenesis.repository.compliance.openssf;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the curated malicious-package feed's settings, so they surface on the settings screens exactly when this
 * module is installed.
 */
public final class OpenSsfSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("openssf", "Compliance", "OpenSSF malicious packages",
                        "The OpenSSF malicious-packages dataset as OSV.dev serves it: curated records naming package "
                                + "versions known to be malicious, each one found for a coordinate a malicious finding "
                                + "the malware action decides. A lookup is an outbound call to a public API. It fails "
                                + "closed: while it is on and cannot be reached, a publish it would have screened is "
                                + "held for review - so switch it on only where this deployment can reach the endpoint "
                                + "below.",
                        Setting.Kind.BOOLEAN, "false", true).essential(),
                new Setting("openssf-endpoint", "Compliance", "OpenSSF feed endpoint",
                        "The OSV API base URL serving the dataset, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://api.osv.dev", true).advanced());
    }
}
