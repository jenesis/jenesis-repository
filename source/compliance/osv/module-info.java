/**
 * The OSV (osv.dev) vulnerability feed: a {@link build.jenesis.repository.compliance.SignalSourceProvider} answering to
 * {@code osv}, discovered by the compliance gate through {@code ServiceLoader}, which keeps the log of what OSV changed
 * in its own signal space.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.alias us.springett.cvss us.springett/cvss-calculator
 */
module build.jenesis.repository.compliance.osv {
    requires build.jenesis.repository.compliance;
    // The bounded feed client this module fetches through; a support module, so not transitive.
    requires build.jenesis.repository.feed;
    // The storage manifest its change log's space is declared through.
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    requires us.springett.cvss;
    exports build.jenesis.repository.compliance.osv;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.osv.OsvAdvisorySourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.osv.OsvSettingsContributor;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.compliance.osv.OsvStorageNamespace;
}
