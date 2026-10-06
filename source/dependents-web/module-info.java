/**
 * The declared-dependencies query surface, contributed through
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}: a thin adapter over the {@code Repositories}
 * resolver and the discovered
 * {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider} (the sharded declared-dependencies index): a
 * client asks who declares a dependency on a package and reads a single small-object shard, never a scan. With no index module
 * installed the {@code /api/dependents} route answers {@code 501}, with the index not yet built it answers {@code 503},
 * and with this module absent the server carries no dependents route. Open so Spring can reflect over the controller
 * and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependents.web {
    exports build.jenesis.repository.dependents.web to build.jenesis.repository.dependents.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.closure.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.dependents.web.DependentsWebModule;
    // The console seam this module contributes its screen through, and the read service it renders.
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires thymeleaf.spring6;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.dependents.web.DependentsConsoleModule;
}
