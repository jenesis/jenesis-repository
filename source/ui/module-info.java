/**
 * The web console: Spring Boot on embedded Jetty, Thymeleaf views and Spring Security with OAuth2/OIDC login, an open
 * module for Spring's reflection. A screen is contributed through {@code ConsoleModuleProvider}, the one GUI extension
 * seam, so a capability is added by putting its module on the graph; a contribution names the fragment its body renders
 * through and prepares its model, so nothing contributed produces markup. Login mechanisms plug in through the
 * {@code LoginContributor} bean seam. The format SPI is required so the browse can mark each namespace with its
 * format's mark, or as an orphan.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    // The console's authority model is grants, read through Authorization.
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
    requires spring.security.oauth2.jose;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.starter.thymeleaf;
    requires spring.boot.starter.security;
    requires spring.boot.starter.oauth2.client;
    exports build.jenesis.repository.ui;
    uses build.jenesis.repository.ui.ConsoleModuleProvider;
    uses build.jenesis.repository.ui.ConsoleLayout.Extension;
    // Transitive: a provider overriding IconContributor.icon() names IconResource in its signature.
    requires transitive build.jenesis.repository.icon;
}
