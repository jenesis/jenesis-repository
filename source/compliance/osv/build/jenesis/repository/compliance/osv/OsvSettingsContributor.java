package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the OSV feed's settings, so they surface on the settings screens exactly when this module is installed.
 */
public final class OsvSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("osv", "Compliance", "OSV feed",
                        "Consult the OSV (osv.dev) vulnerability feed. Off unless you turn it on: a lookup is an outbound call to a public API, and a deployment that configured nothing has not agreed to make one."
                                + " It fails closed: while it is on and cannot be reached, a publish it would have screened is held for review rather than admitted unscreened - so switch it on only where this deployment can reach the endpoint below, and read a hold's reason before taking it for a verdict on the content.",
                        Setting.Kind.BOOLEAN, "false", false),
                new Setting("osv-endpoint", "Compliance", "OSV endpoint",
                        "The OSV API base URL, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://api.osv.dev", false));
    }
}
