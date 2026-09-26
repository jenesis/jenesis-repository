/**
 * The export screen: an operator publishes a whole repository to another one from the admin console.
 *
 * <p>A console module rather than part of the console, because that is what the extension seam is for - it registers
 * its own screen and menu entry through {@code ConsoleModuleProvider}, and a composition without the export module
 * does not carry it. The screen calls the export module's own {@code Exports} in process, which is the code the API
 * answers from, so the console and the API cannot disagree about which URL is refused or what a job has done.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.export.web {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.export;
    requires build.jenesis.repository.format;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires thymeleaf.spring6;
    exports build.jenesis.repository.export.web;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.export.web.ExportConsoleModule;
}
