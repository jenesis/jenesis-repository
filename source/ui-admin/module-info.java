/**
 * The Spring Boot admin console, an open module for Spring's reflection, binding the Spring-free domain layer
 * ({@code ui.store}) and the product's SPI modules directly.
 *
 * <p>{@code tomcat-embed-el} is excluded from the Jetty starter: as the automatic module
 * {@code org.apache.tomcat.embed.el} it exports {@code jakarta.el} beside the real module, and the boot layer refuses to
 * resolve. Exclusions are inherited from sibling modules, so removing one copy proves nothing until every copy goes.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 */
open module build.jenesis.repository.ui.admin {
    exports build.jenesis.repository.ui.admin;
    exports build.jenesis.repository.ui.admin.extension;
    exports build.jenesis.repository.ui.admin.security;
    exports build.jenesis.repository.ui.admin.config;
    exports build.jenesis.repository.ui.admin.web;
    requires transitive build.jenesis.repository.ui.store;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.failure;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.demo;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.scope;
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
    requires build.jenesis.repository.search;
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
