/**
 * The published-index HTTP surface, contributed through
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}: a thin adapter over
 * {@link build.jenesis.repository.index.PublishedIndex}, resolved per tenant and repository. {@code GET /api/index}
 * serves the chain descriptor and {@code GET /api/index/chunks/{id}} streams an immutable, content-addressed chunk, so
 * a consumer syncs by fetching the descriptor and only the chunks it has not seen. Open so Spring can reflect over the
 * controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.index.web {
    exports build.jenesis.repository.index.web;
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
