/**
 * The Conda channel format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /conda/...} - streaming {@code .conda} and {@code .tar.bz2} uploads whose embedded {@code info/index.json}
 * alone is read (Commons Compress, with zstd for {@code .conda}), the per-subdir {@code repodata.json} maintained on
 * write, and downloads - with an {@link build.jenesis.repository.format.ArtifactLayout} declaring the {@code "conda"}
 * ecosystem. It proxies an upstream channel and imports a {@code conda} channel through its own publish path.
 *
 * @jenesis.release 25
 * @jenesis.alias aircompressor io.airlift/aircompressor-v3
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.conda {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires aircompressor;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.conda;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.conda.CondaFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.conda.CondaListingObserver;
}
