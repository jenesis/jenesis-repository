/**
 * The published-index HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its
 * configuration through {@code ServiceLoader} discovery and names no index endpoint. A thin Spring {@code web}
 * adapter over the framework-free {@link build.jenesis.repository.index.PublishedIndex}, resolved per
 * tenant-and-repository through {@code Repositories}: {@code GET /api/index} serves the chain
 * descriptor and {@code GET /api/index/chunks/{id}} streams an immutable, content-addressed chunk with an
 * {@code ETag} and {@code immutable} caching, so a consumer's sync is fetch-descriptor, diff, fetch-only-unseen. With
 * this module absent the server carries none of the surface. Open so Spring can reflect over the controller and its
 * configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.index.web {
    exports build.jenesis.repository.index.web to build.jenesis.repository.server.kernel.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.index;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.index.web.IndexWebModule;
}
