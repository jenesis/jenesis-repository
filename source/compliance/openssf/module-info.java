/**
 * The curated OpenSSF malicious-packages feed: it provides
 * {@link build.jenesis.repository.compliance.SignalSourceProvider} (an advisory feed) answering to {@code openssf}. The
 * dataset (github.com/ossf/malicious-packages, Apache-2.0) is
 * consumed through the OSV.dev API that serves it, filtered to its {@code MAL-} records, each flagged malicious, and
 * composed with every other enabled feed, de-duplicated.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.openssf {
    requires build.jenesis.repository.compliance;
    // The bounded feed client, which owns the HTTP client, the caps, the origin guard and the retry schedule.
    requires build.jenesis.repository.feed;
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.openssf;
    provides build.jenesis.repository.compliance.SignalSourceProvider
            with build.jenesis.repository.compliance.openssf.OpenSsfMaliciousSourceProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.openssf.OpenSsfSettingsContributor;
}
