/**
 * The OSV (osv.dev) vulnerability feed: a {@link build.jenesis.repository.compliance.SignalSourceProvider} answering to
 * {@code osv}, discovered by the compliance gate through {@code ServiceLoader}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.osv {
    requires build.jenesis.repository.compliance;
    // The bounded feed client this module fetches through; a support module, so not transitive.
    requires build.jenesis.repository.feed;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.osv;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.osv.OsvAdvisorySourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.osv.OsvSettingsContributor;
}
