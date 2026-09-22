/**
 * Tests of the repository CLI client in isolation: the session round-trips through its home directory, and the API
 * client parses the settings list and sends the right authenticated set and clear requests against a loopback WireMock
 * server standing in for the repository. No real server and no real home directory.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cli
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cli.test {
    requires build.jenesis.repository.cli;
    requires java.net.http;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
