/**
 * The CocoaPods registry format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /cocoapods/...} CDN layout - streaming pod uploads whose embedded {@code .podspec.json} alone is read, the
 * version document, the sharded version listings kept as stored listings, podspecs generated on read, and downloads -
 * with an {@link build.jenesis.repository.format.ArtifactLayout} declaring the {@code "CocoaPods"} ecosystem. It
 * proxies an upstream CDN, rewriting an http-zip {@code source} through this registry, and imports a {@code cocoapods}
 * repository through its own publish path.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.cocoapods {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.cocoapods to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.gateway.contract.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.cocoapods.CocoaPodsFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.cocoapods.CocoaPodsListingObserver;
}
