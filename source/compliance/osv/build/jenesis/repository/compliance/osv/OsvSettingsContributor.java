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
                        "The OSV (osv.dev) vulnerability database, which gathers the advisories of each ecosystem's "
                                + "own databases: for each package version, the advisories affecting it, each with its "
                                + "id, CVE aliases, severity and the versions that fix it. The gate checks each "
                                + "version against them - the vulnerability threshold and action decide on an "
                                + "advisory, the malware action on a malicious-package record - and the scheduled scan "
                                + "re-checks what is already published. A lookup is an outbound call to a public API, "
                                + "answered for an hour from memory. It fails closed: while it is on and cannot be "
                                + "reached, a publish it would have screened is held for review rather than admitted "
                                + "unscreened - so switch it on only where this deployment can reach the endpoint "
                                + "below, and read a hold's reason before taking it for a verdict on the content.",
                        Setting.Kind.BOOLEAN, "false", false).essential(),
                new Setting("osv-endpoint", "Compliance", "OSV endpoint",
                        "The OSV API base URL, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://api.osv.dev", false).advanced());
    }
}
