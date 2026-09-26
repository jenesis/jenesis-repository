/**
 * The one HTTP client this product makes outbound calls with: a {@code java.net.http.HttpClient} of its own, whose
 * transport is Jetty's, so every caller keeps the JDK's request, response and body-handler types while the
 * connection is made by a client this product controls.
 *
 * <p><b>Why not the JDK's own.</b> Two things the JDK client cannot be told. It resolves a host inside its
 * implementation, with no seam, so a private-address screen that resolved the name a moment earlier cannot hold the
 * connect to what it checked - a name that rebinds between the two reaches an internal address the screen refused.
 * Jetty's client resolves through a {@code SocketAddressResolver} this module supplies, which holds a host the screen
 * admitted as public to public addresses. And the JDK client announces the runtime in every request
 * ({@code User-Agent: Java-http-client/<version>}), which says nothing an upstream needs and tells anyone reading it
 * which runtime to aim at.
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
