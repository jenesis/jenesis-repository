/**
 * The format-native import connector: an {@link build.jenesis.repository.importer.ImportSourceProvider} answering to
 * {@code index} that walks the format's own published mirror-style index - the PEP 503 project list, an OCI registry's
 * {@code /v2/_catalog}, a {@code repodata} or {@code Packages} index - through
 * {@link build.jenesis.repository.format.ProxyFormat#enumerate}, so migration in is vendor-neutral for every installed
 * format that can enumerate. The format is named up front, resolved among the installed
 * {@link build.jenesis.repository.format.RepositoryFormat}s, and each enumerated coordinate streams lazily to the
 * orchestrator, which routes it to that format's importer; the connector knows no ecosystem. This product serves the
 * same standard indexes, so it can be walked by this connector too.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer.index {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.importer.index;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.index.IndexSourceProvider;
}
