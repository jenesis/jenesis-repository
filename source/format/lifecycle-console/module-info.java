/**
 * The console's Lifecycle page: a repository's deprecated and yanked versions, a page at a time, and the form that
 * marks or clears one.
 *
 * <p>A console module, registered through {@code ConsoleModuleProvider}, so a composition without the lifecycle API
 * carries no page for it. The page calls {@code LifecycleMarks} in process - the service {@code /api/lifecycle}
 * answers from - so the two surfaces refuse the same repositories, disclose the same marks and audit a change under
 * the same name.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.lifecycle.console {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.format.lifecycle.web;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.store;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires thymeleaf.spring6;
    exports build.jenesis.repository.format.lifecycle.console;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.format.lifecycle.console.LifecycleConsoleModule;
}
