/**
 * The build-cache server, a Spring Boot app on the same stack as the admin console. It requires the
 * Spring modules its code compiles against plus the web starter to root the runtime closure (embedded
 * Jetty, Jackson) through Maven, and the storage SPI. The cache has no backend of its own: it delegates
 * to the repository's store, which a deployment selects once with {@code JENREG_STORE}. Open so Spring
 * can reflect over the beans/controller;
 * the {@code build.jenesis.repository.cache.server} package is exported so the server is embeddable and testable.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 */
open module build.jenesis.repository.cache.server {
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.protocol;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    requires micrometer.core;
    requires com.github.benmanes.caffeine;
    requires build.jenesis.repository.store.metering;
    requires build.jenesis.repository.observation;
    requires micrometer.registry.prometheus;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    exports build.jenesis.repository.cache.server;
    requires build.jenesis.repository.settings;
    provides build.jenesis.repository.settings.SettingsContributor with build.jenesis.repository.cache.server.CacheNodeSettingsContributor;
}
