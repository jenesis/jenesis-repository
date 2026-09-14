/**
 * The web console: a Spring Boot admin front for the repository, built on a mainstream Spring stack
 * (Spring Boot on embedded Jetty, Thymeleaf views, Spring Security with OAuth2/OIDC login) so a downstream distribution
 * extends this shell rather than forking it. It is an open module (Spring needs reflective access) and requires the
 * Spring modules its code compiles against plus the Spring Boot starters that root the runtime closure (embedded
 * Jetty, Thymeleaf, Jackson, Security, OAuth2 client). Built as an open shell with a card-registration SPI
 * ({@code uses ConsoleCard}) - this console's own overview page, not the GUI extension seam, which is
 * {@code ConsoleModuleProvider} - discovered with ServiceLoader and bridged into Spring, so additional cards are
 * registered by adding modules to the graph, with no fork of the console. A card names the Thymeleaf fragment its
 * body renders through and prepares the value that fragment reads, so no contribution to this console produces
 * markup. Login mechanisms plug in the same way through the
 * {@code LoginContributor} bean seam.
 *
 * <p>It requires the format SPI for one reason: the browse card marks each published namespace with the mark of the
 * format that owns it, resolved through the shared {@code Marks} every contributing plug-in family renders through,
 * so a namespace no installed format claims is shown as the orphan it is rather than as an ordinary row. That is an
 * SPI dependency, not a plugin one - the console still requires no concrete format and discovers them all through
 * the SPI's own lookup.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    // The console's authority model is grants now, and a grant is an Authorization. A small closure: this
    // module is java.base plus the store, which the console already requires.
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires jakarta.servlet;
    requires micrometer.observation;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    requires spring.boot.autoconfigure;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.security.oauth2.client;
    requires java.net.http;
    requires tools.jackson.databind;
    requires spring.security.oauth2.core;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.starter.thymeleaf;
    requires spring.boot.starter.security;
    requires spring.boot.starter.oauth2.client;
    exports build.jenesis.repository.ui;
    uses build.jenesis.repository.ui.ConsoleCard;
    uses build.jenesis.repository.ui.ConsoleModuleProvider;
    uses build.jenesis.repository.ui.ConsoleLayout.Extension;
    provides build.jenesis.repository.ui.ConsoleCard
            with build.jenesis.repository.ui.BrowseCard,
                    build.jenesis.repository.ui.LogCard,
                    build.jenesis.repository.ui.ConsistencyCard,
                    build.jenesis.repository.ui.CredentialsCard;

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
