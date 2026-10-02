/**
 * The walks screen: an operator schedules the walks of the store and sees what each one costs.
 *
 * <p>It registers its screen and menu entry through {@code ConsoleModuleProvider}. The screen edits the {@code walks}
 * document - entries of a cron expression and the consumers that ride it - and says, before an operator schedules one,
 * that a walk reads every object in the store and what the last run of each entry measured. It reaches
 * {@code WalkRuns}, as the admin endpoint does, so the console, the API and the CLI are one capability; the document
 * itself is the {@code walks} setting.
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.walk.web {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.walk.task;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.store;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.security.core;
    requires thymeleaf.spring6;
    exports build.jenesis.repository.walk.web;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.walk.web.WalksConsoleModule;
}
