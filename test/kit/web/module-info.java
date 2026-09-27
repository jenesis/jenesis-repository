/**
 * The wiring a server or console controller needs to be driven in process, once, for both trees.
 *
 * <p>Every {@code *-web} module is the same shape - a provider, a controller and a config class - and every one of
 * those controllers takes some subset of the same few collaborators: the store, the kernel's repository view, the
 * routing, the settings pins, the maintenance scheduler and an audit trail. {@code Web} builds them the way a
 * deployment does, over a real filesystem store, so a suite constructs its controller and calls it without a Spring
 * context and stays in the quickest lane. The servlet kit's {@code Servlets} comes with it, for the request a
 * handler reads and the response it writes.
 *
 * <p>No JUnit and no assertion library: the classes are fixtures, nothing here provides a service, and the module
 * is inert on a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.web.testkit {
    requires transitive build.jenesis.repository.server.kernel;
    requires transitive build.jenesis.repository.server;
    requires transitive build.jenesis.repository.server.spi;
    requires transitive build.jenesis.repository.audit;
    requires transitive build.jenesis.repository.store;
    requires transitive spring.core;
    requires transitive build.jenesis.repository.servlet.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.gateway;
    requires micrometer.core;
    exports build.jenesis.repository.web.testkit;
}
