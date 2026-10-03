/**
 * The export driven in process against a local server: the HTTP target - which paths it refuses to address, that it
 * follows no redirect, which credential header it adds and when it leaves the exporter's own, and how much of an
 * answer it keeps - and the export itself, a background job over a real filesystem store sending a raw and a Maven
 * repository's published content, watched through its stored state from the API and the console screen alike.
 * In-process throughout - the JDK's own HTTP server on a loopback port, no container and no registry.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.export
 * @jenesis.test build.jenesis.repository.export.web
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.export.test {
    requires build.jenesis.repository.export;
    requires build.jenesis.repository.export.http;
    requires build.jenesis.repository.export.web;
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.ui;
    requires spring.context;
    requires spring.webmvc;
    requires build.jenesis.repository.format;
    requires jdk.httpserver;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
