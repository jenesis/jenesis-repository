/**
 * The server and console handlers of the free core's {@code *-web} modules, driven in process: the walks screen and
 * its API, the deploy screen publishing through the repository's own edge, the deployment-config surface, the
 * repository-maintenance surface, the management API, and browse and search.
 *
 * <p>Each handler is constructed over a real filesystem store with the shared {@code Web} wiring and called
 * directly - no Spring context, no server, no container - so what a suite asserts is what the handler answered and
 * what the store holds afterwards. The retention, rate-limiting and raw-format modules are on this path so that the
 * handlers which answer "not installed" without them reach their real paths here; the Maven format places the
 * ecosystem the maintenance suite records its releases under.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.walk.web
 * @jenesis.test build.jenesis.repository.deploy.web
 * @jenesis.test build.jenesis.repository.config.web
 * @jenesis.test build.jenesis.repository.cleanup.web
 * @jenesis.test build.jenesis.repository.management.web
 * @jenesis.test build.jenesis.repository.search.web
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.web.test {
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.walk.web;
    requires build.jenesis.repository.walk.task;
    requires build.jenesis.repository.deploy.web;
    requires build.jenesis.repository.config.web;
    requires build.jenesis.repository.cleanup.web;
    requires build.jenesis.repository.management.web;
    requires build.jenesis.repository.search.web;
    requires build.jenesis.repository.search.service;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.cleanup.task;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.ui;
    requires micrometer.observation;
    requires spring.context;
    requires spring.web;
    requires spring.webmvc;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
