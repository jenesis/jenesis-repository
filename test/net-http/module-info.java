/**
 * Unit tests of the product's HTTP client: what a request carries and does not, how bodies travel both ways, how
 * redirects, timeouts and refused connections answer, and that a host a private-address screen admitted as public
 * is not connected to once its name rebinds to a private address. Every exchange is with a loopback server in the
 * test's own JVM.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.net.http
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.net.http.test {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.net;
    requires jdk.httpserver;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
