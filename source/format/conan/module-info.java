/**
 * The Conan (C/C++) registry format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /conan/...} Conan v2 REST protocol, so {@code conan upload} and {@code conan install} resolve C/C++
 * packages over the shared store. It owns {@code /conan/<repo>/v2/...}: a capability {@code ping}
 * ({@code X-Conan-Server-Capabilities: revisions}, so a Conan 2 client uses the revisions API), a recipe or package
 * <i>file</i> streamed straight into the content-addressed store on a
 * {@code PUT /v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>} (and the package equivalent
 * under {@code .../packages/<package_id>/revisions/<prev>/files/<file>}), and the revision index a client reads -
 * {@code latest}, {@code revisions} and the per-revision {@code files} listing, for both the recipe and its packages -
 * a stored listing each upload updates with its one entry, streamed as it is on read. A file downloads from the same {@code files/<file>} path it was
 * uploaded to. The client computes the recipe and package revision hashes itself and uploads to them, so this format is
 * a streaming, revision-addressed file store with an index maintained on write - nothing is buffered and no archive is
 * cracked (the coordinate is in the request path). A {@link build.jenesis.repository.store.PublicationObserver} keeps
 * the index in step with a hold, a release and a removal. It also provides
 * {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "Conan"} ecosystem and resolving a
 * package/recipe file download path to its {@code <name>} coordinate and version. JSON index documents are emitted with
 * the Jackson databind on the server's module path (a library, not a hand-rolled writer). Discovered through
 * {@code provides}. It also implements {@link build.jenesis.repository.format.ProxyFormat}: a local
 * {@code v2/conans/...} miss is served from an upstream Conan server (ConanCenter by default), an immutable
 * revision-pinned file cached into the store and served locally while the mutable {@code latest}/{@code revisions}/
 * {@code files} index is streamed through fresh - the dispatcher detects the local miss and checks
 * {@code instanceof ProxyFormat}, so no {@code provides} change is needed. Finally it provides a
 * {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory {@code conan} registry,
 * replaying each revision file (its coordinate and client-computed revision preserved in the path) as a streaming
 * publish through the format's own {@code PUT} path so an exported repository round-trips. The compliance inspector is a
 * sibling module. Format-native enumeration is honestly unsupported: the Conan protocol exposes only a
 * {@code search} query, no mirror-style walkable index, so the format keeps {@code ProxyFormat.enumerate}'s empty
 * default and an {@code index}-source migration of a Conan repository imports nothing - use the Nexus/Artifactory
 * vendor connectors instead.
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
    // Exported to test modules only: the format's classes are wiring for the gateway, not API. The unit suite is
    // named here for the same reason the gateway's is - its assertions are about the path grammar, which is a pure
    // function of a string and belongs in the fastest lane rather than behind a container.
    exports build.jenesis.repository.format.conan to
            build.jenesis.repository.gateway.test,
            build.jenesis.repository.format.conan.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.conan.ConanFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.conan.ConanListingObserver;
}
