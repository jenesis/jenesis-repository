/**
 * The Composer registry format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /composer/...} Composer-v2 layout - streaming package uploads whose embedded {@code composer.json} alone is
 * read, a generated {@code packages.json}, stored {@code p2} metadata and downloads - and an
 * {@link build.jenesis.repository.format.ArtifactLayout} declaring the {@code "Packagist"} ecosystem. It proxies an
 * upstream Composer-v2 repository, rewriting each {@code dist.url} through this registry, and imports a
 * {@code composer} repository through its own publish path.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.composer {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.composer to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.gateway.contract.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.composer.ComposerFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.composer.ComposerListingObserver;
}
