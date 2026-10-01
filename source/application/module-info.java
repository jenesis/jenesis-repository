/**
 * The repository server's composition root: {@code RepositoryApplication}, the {@code @Configuration} groups, the
 * security contributions and the bridge that imports the discovered feature modules
 * ({@code ServerModuleImports}), composed over the runtime kernel {@code build.jenesis.repository.server.kernel}.
 * Only the bundle and the suites that boot it name it: feature modules require the kernel and are discovered from
 * here. Component scan covers only this package, so every kernel bean Spring must see is declared by a {@code @Bean}
 * method here. Open so Spring can reflect over the beans and controllers.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 *
 */
open module build.jenesis.repository.application {
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.ui;
    requires jakarta.servlet;
    // Claims the import edge, so the server's own import controller is not created beside ImportController.
    provides build.jenesis.repository.server.spi.ImportEdgeProvider
            with build.jenesis.repository.application.RoutedImportEdge;
    requires micrometer.observation;
    requires micrometer.core;
    requires build.jenesis.repository.store.metering;
    requires micrometer.registry.prometheus;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    // Jetty's own API, for the encoded-slash customizer (EncodedSlashConfig).
    requires spring.boot.jetty;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.ee11.servlet;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.boot.starter.security;
    // The composition, for the bundles that ship it and the suites and harnesses that boot it.
    exports build.jenesis.repository.application;
}
