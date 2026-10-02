/**
 * The deploy screen: an operator publishes an artifact from the admin console. It registers its own screen through
 * {@code ConsoleModuleProvider}, so a deployment that wants no upload surface leaves it out, and the repository's write
 * edge and the tenant binding stay out of the console module's closure.
 *
 * <p><b>It publishes through the edge, never into the store.</b> The upload goes to
 * {@code RepositoryController.publish}, which routes it, refuses a target that takes no write, screens the body through
 * the interceptor chain once and lets the claiming format lay out what is accepted, so the screen is no way around the
 * compliance gate. The body is streamed through the shared bounded multipart reader, since no application in this
 * product runs a {@code MultipartResolver}.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.deploy.web {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.multipart;
    requires build.jenesis.repository.store;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.security.core;
    requires thymeleaf.spring6;
    requires jakarta.servlet;
    exports build.jenesis.repository.deploy.web;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.deploy.web.DeployConsoleModule;
}
