/**
 * The demo the first-run guide offers, loaded in process over a filesystem store: the repositories it creates, the
 * packages it publishes through the repository's own edge with the deployment's gate armed, the one the deny list
 * holds, the settings it switches on as the operator, and what it reports when the public registries are not reached -
 * here they never are, since nothing in this module reaches the network.
 *
 * <p>The Maven and npm formats are on the path so the demo's hosted and proxy halves both have a format to create;
 * the Maven inspector so the deny list can name the held library's coordinate; the gate so a publish is screened at
 * all; and the OSV module so the feed the demo switches on is a setting the catalogue holds.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.demo
 * @jenesis.test build.jenesis.repository.demo.web
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.demo.test {
    requires build.jenesis.repository.demo;
    requires build.jenesis.repository.demo.web;
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.settings;
    requires micrometer.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
