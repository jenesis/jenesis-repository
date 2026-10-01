/**
 * The runtime-settings contracts: a typed descriptor for one editable setting and the SPI a module implements to
 * contribute its settings to the deployment's catalogue, kept light so any module can describe its settings, and the
 * console, API and CLI list exactly what is installed. It also carries the posture advisor for the core's
 * tenant-overridable gate dials, {@link build.jenesis.repository.settings.TenantPosture}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.settings {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.net;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.observation;
    requires tools.jackson.databind;
    exports build.jenesis.repository.settings;
    uses build.jenesis.repository.settings.SettingsContributor;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.settings.CoreSettingsContributor;
    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.settings.TenantPosture;
}
