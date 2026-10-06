/**
 * The dependents surface - {@code /api/repository/dependents} and the console's Dependents screen - contributed through
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} and the console seam: what depends on a version,
 * as its resolved dependents (the closure's {@link build.jenesis.repository.closure.spi.Reliance}) and its declared
 * ones (the discovered {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider}), each a bounded page,
 * never a scan. A half that cannot answer says so in the answer - the declared index not installed, or not built yet -
 * and with this module absent the server carries no dependents route. Open so Spring can reflect over the controllers
 * and their configuration.
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
    requires build.jenesis.repository.server.spi;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.dependents.web.DependentsCapabilityContributor;
    // The console seam this module contributes its screen through, and the read service it renders.
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires thymeleaf.spring6;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.dependents.web.DependentsConsoleModule;
}
