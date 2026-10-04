package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the OSV feed's settings, so they appear exactly when this module is installed. */
public final class OsvSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("osv", "Compliance", "OSV feed",
                        "The OSV vulnerability database (osv.dev), gathering each ecosystem's own advisory databases: "
                                + "the advisories affecting each package version, with severity and fixed versions, "
                                + "and malicious-package records. The gate checks each version against them and the "
                                + "scheduled scan re-checks what is published. A lookup is an outbound call to a "
                                + "public API. It fails closed: while it is on and unreachable, a publish it would "
                                + "have screened is held for review.",
                        Setting.Kind.BOOLEAN, "false", true).essential(),
                new Setting("osv-endpoint", "Compliance", "OSV endpoint",
                        "The OSV API base URL, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://api.osv.dev", true).advanced());
    }
}
