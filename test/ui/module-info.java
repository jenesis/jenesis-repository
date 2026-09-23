/**
 * Unit tests for the console shell: the layout's extension points, the url space both chains are matched over, the
 * access and principal seams, the login options a mechanism contributes, and the posture and OIDC discovery reads.
 *
 * <p>It drives the classes in process. Booting a console over HTTP is an end-to-end concern and lives in the
 * other tree with the rest of them, against the console the images actually serve - this module's own shell node
 * is gone, and with it the temptation to prove the product against a composition nobody runs.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 * @jenesis.test build.jenesis.repository.ui
 * @jenesis.attach org.mockito
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.test {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.contract.testkit;
    requires java.net.http;
    requires spring.core;
    requires spring.beans;
    requires spring.context;
    requires spring.web;
    requires jakarta.servlet;
    requires spring.security.core;
    requires spring.security.web;
    requires jdk.httpserver;
    requires spring.security.oauth2.client;
    requires spring.security.oauth2.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires org.mockito;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.ui.test.NavigatingConsoleModule;
    // A format declaring a mark, discovered the same way, so the booted console resolves a namespace's mark through
    // the panel's own ServiceLoader path rather than only through a lookup a unit test hands in. No format module is
    // otherwise on the console's graph.
    provides build.jenesis.repository.format.RepositoryFormat with build.jenesis.repository.ui.test.MarkedFormat;
}
