/**
 * Export: publish everything a repository holds to another repository as each format's own client would, so leaving
 * this product needs no importer on the other side - part of the free core by design. A background job walks the
 * versions and hands each to its format's {@link build.jenesis.repository.format.RepositoryExporter} over an HTTP
 * target that follows no redirect and refuses a private or plaintext address unless migration to one is allowed. The
 * job's state is a small document in the repository's store, so it survives a restart and resumes; the credential is
 * held in memory only. {@code POST /api/repository/export?repo=} starts a job and
 * {@code GET /api/repository/export/{id}?repo=} reads it.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.export {
    requires build.jenesis.repository.net.http;
    exports build.jenesis.repository.export;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.scope;
    requires java.net.http;
    requires jakarta.servlet;
    requires tools.jackson.databind;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.export.ExportModule;
}
