/**
 * The runtime-settings contracts: a typed descriptor for one editable setting and the {@code ServiceLoader} SPI a
 * plugin module implements to contribute its settings to the deployment's catalogue. Light, so any module - a
 * compliance feed, a retention engine, a storage backend - describes its settings without pulling in anything it does
 * not already carry (the JSON library every module has is the one dependency beyond the core contracts), and the
 * console, API and CLI list exactly what is installed.
 *
 * <p>It also owns the posture advisories about its own dials: {@link build.jenesis.repository.settings.TenantPosture}
 * is the {@code SafetyAdvisor} for the tenant-overridable compliance-gate knobs {@link
 * build.jenesis.repository.settings.CoreSettingsContributor} declares, and is the first advisor in either repository
 * to raise a {@code TENANT}-scoped advisory. The posture SPI is {@code java.base}-only like this module, so the
 * dependency keeps the contract module light.
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
