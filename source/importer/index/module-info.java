/**
 * The format-native enumeration import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} answering to {@code index} that walks the
 * <em>format's own</em> published mirror-style index - the PEP 503 project list, an OCI registry's
 * {@code /v2/_catalog}, a {@code repodata}/{@code Packages} index - through the
 * {@link build.jenesis.repository.format.ProxyFormat#enumerate} seam, so migration-in is vendor-neutral for every
 * installed format that can enumerate, not just Maven. The requested ecosystem format is named up front; the
 * provider resolves it among the installed {@link build.jenesis.repository.format.RepositoryFormat}s and streams
 * each enumerated coordinate lazily to the orchestrator, which routes it to that format's own importer - so the
 * connector itself knows no ecosystem, and a new format's repositories become importable the moment its module
 * implements {@code enumerate}. Because jenesis emits these same standard indexes to serve native clients, a
 * jenesis repository is walkable by this connector too: migration off jenesis works over plain format protocols,
 * in both directions. Depends only on the import SPI and the format SPI.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.importer.index {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.importer.index to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.index.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.index.IndexSourceProvider;
}
