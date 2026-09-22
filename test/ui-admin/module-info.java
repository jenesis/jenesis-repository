/**
 * The admin console's screens and services in process: what each renders and refuses, who may reach it, how a
 * contributed module's failure is contained, and what the console reads back from the store.
 *
 * <p>Nothing here boots a server or drives a browser - those suites live where the placement rule puts them -
 * so every claim below answers in the quickest lane.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.ui.admin
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.admin.test {
    requires build.jenesis.repository.ui.admin;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.storage.delegating;
    requires build.jenesis.repository.cache.storage.testkit;
    requires micrometer.observation;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.security.core;
    requires spring.security.web;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.ui.ConsoleLayout.Extension;
    // A console module whose navEntries() throws. Registered for the whole module on purpose: the fan-out runs
    // at construction, so a regression in its containment fails every suite here rather than one leg.
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.ui.admin.test.HostileConsoleModule;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.ui.admin.test.ContributedFlags;
}
