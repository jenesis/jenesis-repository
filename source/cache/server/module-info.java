/**
 * The build-cache server, a Spring Boot app on the console's stack: the Spring modules its code compiles against, the
 * web starter rooting the runtime closure, and the storage SPI. The cache delegates to the repository's store, selected
 * once with {@code JENREPO_STORE}. Open for Spring's reflection; its package is exported so the server is embeddable
 * and testable.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 */
open module build.jenesis.repository.cache.server {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.scope;
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
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.cache.server.CacheProtocolsCapabilityContributor;
}
