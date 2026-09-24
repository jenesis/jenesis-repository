/**
 * The GitHub Advisory Database feed as a plugin module: it provides
 * {@link build.jenesis.repository.compliance.SignalSourceProvider} (an advisory feed) answering to {@code github}, so the compliance
 * gate discovers it through {@code ServiceLoader} and never names GitHub. Composed with any other enabled feed and
 * de-duplicated; a new feed is added the same way, as its own module.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.github {
    requires org.slf4j;
    requires build.jenesis.repository.compliance;
    // The bounded feed client: this module fetches through it and never re-rolls an HTTP client, a status
    // branch, a page cap or a retry schedule. It is a support module, so the requires is deliberately not transitive.
    requires build.jenesis.repository.feed;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.github to build.jenesis.repository.compliance.github.test,
            build.jenesis.repository.compliance.test;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.github.GitHubAdvisorySourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.github.GitHubSettingsContributor;
}
