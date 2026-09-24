/**
 * Tests of the GitHub Advisory Database source against a recorded endpoint: the request it sends, the advisories it
 * reads back with their severity bands, and the fail-closed answer to a request that did not succeed.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.github
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.github.test {
    requires build.jenesis.repository.compliance.github;
    requires build.jenesis.repository.compliance;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
