/**
 * The OSV (osv.dev) vulnerability feed: a {@link build.jenesis.repository.compliance.SignalSourceProvider} answering to
 * {@code osv}, discovered by the compliance gate through {@code ServiceLoader}, which keeps the log of what OSV changed
 * in its own signal space - and its mirror, answering to {@code osv-mirror}, which keeps a copy of OSV's export for the
 * ecosystems the repositories selecting it hold and reads versions against it with each ecosystem's own order.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.alias us.springett.cvss us.springett/cvss-calculator
 */
module build.jenesis.repository.compliance.osv {
    requires build.jenesis.repository.compliance;
    // The ecosystems' version orders a mirrored record's ranges are read by.
    requires build.jenesis.repository.closure.spi;
    // The bounded feed client this module fetches through; a support module, so not transitive.
    requires build.jenesis.repository.feed;
    // The storage manifest its change log's space is declared through.
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    requires org.slf4j;
    requires us.springett.cvss;
    exports build.jenesis.repository.compliance.osv;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.osv.OsvAdvisorySourceProvider,
                    build.jenesis.repository.compliance.osv.OsvMirrorSourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.osv.OsvSettingsContributor;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.compliance.osv.OsvStorageNamespace;
}
