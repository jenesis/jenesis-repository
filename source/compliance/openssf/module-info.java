/**
 * The curated OpenSSF malicious-packages feed as a plugin module: it provides
 * {@link build.jenesis.repository.compliance.SignalSourceProvider} (an advisory feed) answering to {@code openssf}, so the compliance
 * gate discovers it through {@code ServiceLoader} and never names the feed. The dataset (github.com/ossf/malicious-packages,
 * Apache-2.0) is consumed through the OSV.dev API that serves it, filtered to its {@code MAL-} records, each flagged
 * malicious. Composed with any other enabled feed and de-duplicated; a new feed is added the same way, as its own module.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.openssf {
    requires build.jenesis.repository.compliance;
    // The bounded feed client: this module fetches through it and never re-rolls an HTTP client, a status
    // branch, a byte cap, a page cap, an origin guard or a retry schedule. It is a support module, so the
    // requires is deliberately not transitive.
    requires build.jenesis.repository.feed;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.openssf to build.jenesis.repository.compliance.openssf.test,
            build.jenesis.repository.compliance.test;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.openssf.OpenSsfMaliciousSourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.openssf.OpenSsfSettingsContributor;
}
