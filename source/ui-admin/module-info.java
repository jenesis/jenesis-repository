/**
 * The Spring Boot admin console as an open module (Spring needs reflective access).
 * It requires the Spring modules its code compiles against, plus the four
 * Spring Boot starters to root the full runtime closure (embedded Jetty,
 * Thymeleaf, Jackson) through the module pins. It binds the Spring-free
 * domain layer ({@code ui.store}) and the product's SPI modules directly.
 *
 * <p><b>Why {@code tomcat-embed-el} is excluded from the Jetty starter.</b> Spring Boot's
 * {@code spring-boot-starter-jetty} declares {@code org.apache.tomcat.embed:tomcat-embed-el} at compile scope -
 * it wants an Expression Language implementation and reaches for Tomcat's, even though the container is Jetty.
 * That jar is the automatic module {@code org.apache.tomcat.embed.el} and it exports {@code jakarta.el}, which
 * the real {@code jakarta.el} module also exports. On a classpath the duplicate is invisible; on a module path
 * the boot layer refuses to resolve, with {@code ResolutionException: Modules jakarta.el and
 * org.apache.tomcat.embed.el export package jakarta.el}.
 *
 * <p>Measured 2026-08-26 by removing the exclusion from all five modules that carry it and booting the console's
 * tests. <b>Removing it from one module proves nothing:</b> an exclusion is inherited by consumers, so a single
 * module's copy is masked by its siblings' and the build stays green - which is exactly the misreading that lets
 * a redundant-looking line survive unexamined. All five have to go before the failure appears.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 */
open module build.jenesis.repository.ui.admin {
    exports build.jenesis.repository.ui.admin to build.jenesis.repository.bundle,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test,
            build.jenesis.repository.ui.admin.browser.test,
            build.jenesis.repository.auth.keylogin.test,
            build.jenesis.repository.bundle.full;
    exports build.jenesis.repository.ui.admin.extension to build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.auth.keylogin.test,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test;
    exports build.jenesis.repository.ui.admin.security to build.jenesis.repository.auth.oidc.test,
            build.jenesis.repository.auth.saml.test,
            build.jenesis.repository.auth.keylogin.test,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test,
            build.jenesis.repository.bundle.full.test;
    exports build.jenesis.repository.ui.admin.config to build.jenesis.repository.auth.oidc.test,
            build.jenesis.repository.auth.saml.test,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test;
    exports build.jenesis.repository.ui.admin.web to build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test;
    requires transitive build.jenesis.repository.ui.store;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.multipart;
    requires build.jenesis.repository.ui;
    provides build.jenesis.repository.ui.ConsoleLayout.Extension
            with build.jenesis.repository.ui.admin.extension.AdminConsoleLayout;
    requires jakarta.servlet;
    requires micrometer.observation;
    requires tools.jackson.databind;
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
    requires spring.security.oauth2.core;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.starter.thymeleaf;
    requires spring.boot.starter.security;
    requires thymeleaf;
    requires thymeleaf.spring6;
    provides build.jenesis.repository.settings.SettingsContributor with build.jenesis.repository.ui.admin.ConsoleSettingsContributor;
}
