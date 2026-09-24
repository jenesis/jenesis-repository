/**
 * The first-run guide asks about every feed and about where to be told: each installed signal source's switch is a
 * key of the feeds step, so a feed added later cannot ship silent on a new deployment, and the notifications step
 * names the webhook dials. The graph carries the feeds and the webhook module the free image carries, and the
 * scheduled scan, whose known-exploited dials stay out of a catalogue that has no known-exploited source.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.settings
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.firstrun.test {
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.scan;
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.compliance.github;
    requires build.jenesis.repository.compliance.openssf;
    requires build.jenesis.repository.webhook;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.settings.SettingsContributor;
}
