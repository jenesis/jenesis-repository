/**
 * The export's HTTP target driven against a local server: which paths it refuses to address, that it follows no
 * redirect, which credential header it adds and when it leaves the exporter's own, and how much of an answer it keeps.
 * In-process throughout - the JDK's own HTTP server on a loopback port, no container and no registry.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.export
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.export.test {
    requires build.jenesis.repository.export;
    requires build.jenesis.repository.format;
    requires jdk.httpserver;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
