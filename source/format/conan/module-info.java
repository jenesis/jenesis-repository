/**
 * The Conan (C/C++) registry format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * the {@code /conan/...} Conan v2 REST protocol - a capability {@code ping}, recipe and package files streamed into the
 * store by {@code PUT} to their revision paths and served from them, and the {@code latest}, {@code revisions} and
 * {@code files} index maintained on write - with an {@link build.jenesis.repository.format.ArtifactLayout} declaring
 * the {@code "Conan"} ecosystem. It proxies an upstream Conan server, ConanCenter by default, and imports a
 * {@code conan} registry by replaying each file through its own {@code PUT}. The protocol exposes only a
 * {@code search}, no walkable index, so {@code ProxyFormat.enumerate} keeps its empty default and an index-source
 * migration imports nothing.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.conan {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires tools.jackson.databind;
    requires org.slf4j;
    // Exported for the suites that drive the format directly.
    exports build.jenesis.repository.format.conan;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.conan.ConanFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.conan.ConanListingObserver;
}
