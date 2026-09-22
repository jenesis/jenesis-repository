/**
 * The deploy screen: an operator publishes an artifact from the admin console.
 *
 * <p>It is a console module rather than part of the console, because that is what the extension seam is for - it
 * registers its own screen and menu entry through {@code ConsoleModuleProvider} and a deployment that does not want
 * an upload surface simply leaves it out. It also keeps the console module's own closure where it is: publishing
 * needs the repository's write edge and the tenant binding, and neither belongs in the closure of a
 * module whose job is to render screens.
 *
 * <p><b>It publishes through the edge, never into the store.</b> The screen hands the upload to
 * {@code RepositoryController.publish}, which routes it, refuses a target that takes no write, screens the body
 * through the discovered interceptor chain exactly once and lets the claiming format lay out what is accepted. An
 * upload screen that scoped a store and wrote blobs would be a hole in the compliance gate rather than a feature,
 * which is the reason this module could not exist before the nodes merged: the console carried the gate and no
 * format at all, so a publish there could be screened and never laid out.
 *
 * <p>The body is streamed. There is no {@code MultipartResolver} in any of this product's applications - it would
 * drain an artifact upload before the format read it, which is how twine and {@code dotnet nuget push} publish - so
 * the upload is read with the shared bounded multipart reader and handed to the edge as a stream.
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
