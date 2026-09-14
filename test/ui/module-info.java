/**
 * End-to-end test of the Spring Boot web console. It boots the real {@link build.jenesis.repository.ui.Application} on
 * an ephemeral port over a temporary filesystem store (supplied here, so the console module itself stays
 * store-agnostic) under the {@code dev} security profile, then drives it over HTTP: the Actuator health endpoint is up,
 * the login page is served anonymously, the console denies an anonymous request, and an authenticated user sees the
 * browse panel rendering the store's real published contents.
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
    // The card census loads the service itself rather than only through UiConfig: the runtime discovery leg and the
    // rendered-set leg are separate assertions, and a `uses` clause is what lets this module make the first one.
    uses build.jenesis.repository.ui.ConsoleCard;
    // A panel that always throws, discovered exactly like a real one, so the booted console in ConsoleE2ETest serves a
    // page that really contains a contained failure - the shell's rendering of it is not provable any other way.
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.ui.test.NavigatingConsoleModule;
    provides build.jenesis.repository.ui.ConsoleCard with build.jenesis.repository.ui.test.FailingCard;
    // A format declaring a mark, discovered the same way, so the booted console resolves a namespace's mark through
    // the panel's own ServiceLoader path rather than only through a lookup a unit test hands in. No format module is
    // otherwise on the console's graph.
    provides build.jenesis.repository.format.RepositoryFormat with build.jenesis.repository.ui.test.MarkedFormat;
}
