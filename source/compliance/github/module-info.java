/**
 * The GitHub Advisory Database feed: a {@link build.jenesis.repository.compliance.SignalSourceProvider} answering to
 * {@code github}, discovered by the compliance gate through {@code ServiceLoader} and composed with every other enabled
 * feed, de-duplicated.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.github {
    requires org.slf4j;
    requires build.jenesis.repository.compliance;
    // The bounded feed client this module fetches through; a support module, so not transitive.
    requires build.jenesis.repository.feed;
    // The storage manifest its change log's space is declared through.
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.github;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.github.GitHubAdvisorySourceProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.compliance.github.GitHubStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.github.GitHubSettingsContributor;
}
