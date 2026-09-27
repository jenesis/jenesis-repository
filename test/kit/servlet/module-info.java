/**
 * The request a handler reads and the response it writes, without a servlet container: {@code Servlets} answers a
 * request's URI, content type, body and headers - a multipart upload among them - and keeps the status, headers and
 * body a handler wrote, so a controller suite states exactly the exchange it means.
 *
 * <p>No JUnit and no assertion library, and nothing but the servlet API: the class is a fixture, nothing here
 * provides a service, and the module is inert on a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.servlet.testkit {
    requires transitive jakarta.servlet;
    exports build.jenesis.repository.servlet.testkit;
}
