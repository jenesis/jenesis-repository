/**
 * The one HTTP client this product makes outbound calls with: a {@code java.net.http.HttpClient} whose transport is
 * Jetty's, so callers keep the JDK's request, response and body-handler types while the connection is made by a client
 * this product controls.
 *
 * <p>The JDK client resolves a host inside its implementation, so a private-address screen cannot hold the connect to
 * the addresses it checked; Jetty resolves through a {@code SocketAddressResolver} this module supplies, which keeps an
 * admitted host on public addresses. And the JDK client announces the runtime in every {@code User-Agent}, which helps
 * only an attacker.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.net.http {
    requires transitive java.net.http;
    requires build.jenesis.repository.net;
    requires org.eclipse.jetty.client;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.util;
    exports build.jenesis.repository.net.http;
}
