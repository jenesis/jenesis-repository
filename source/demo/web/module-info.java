/**
 * The demo an empty deployment is offered from the first page of the first-run guide: repositories, first-party
 * packages published into them - one held for review - versions with known vulnerabilities read through proxies of
 * public registries, and the settings their screens need switched on, so someone trying the product sees every screen
 * populated. It registers its page through {@code ConsoleModuleProvider} and its offer through the guide's
 * {@code SetupOffer} beans, so the guide names no demo, and it loads what every {@code DemoContributor} bean plans -
 * its own content included - so another module adds its sample content to the same run.
 *
 * <p><b>It loads through the edge, never into the store.</b> A package is published through
 * {@code RepositoryController.publish} and a vulnerable version read through {@code RepositoryController.fetch}, the
 * edges a client's {@code PUT} and {@code GET} take, so everything the demo brings in is screened, recorded and
 * listed exactly as a client's would be; a setting is changed through the settings editor every surface uses. The
 * packages are generated in memory and the vulnerable versions fetched on demand, so the module carries no payload
 * and needs the network for its proxy half; offline, the hosted half still loads and the run says what stayed empty.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.demo.web {
    requires build.jenesis.repository.demo;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.observation;
    requires micrometer.observation;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires tools.jackson.databind;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.security.core;
    requires thymeleaf.spring6;
    exports build.jenesis.repository.demo.web;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.demo.web.DemoConsoleModule;
}
