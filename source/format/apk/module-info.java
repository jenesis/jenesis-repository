/**
 * The Alpine {@code apk} repository as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * the plain HTTP layout {@code apk} speaks, {@code /apk/<repo>/<arch>/...}, with a publish as {@code PUT} of the raw
 * {@code .apk} to its path. {@code APKINDEX} is a {@link build.jenesis.repository.store.StoredListing} each publish
 * re-decides, with {@code APKINDEX.tar.gz} its signed, derived twin, and every indexed field read from the package's
 * own {@code .PKGINFO}. It also declares the {@code "Alpine"} ecosystem as an
 * {@link build.jenesis.repository.format.ArtifactLayout}, follows coordinates through {@code BlobLayout}, proxies an
 * upstream Alpine repository and imports one.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.apk {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.settings;
    requires org.apache.commons.compress;
    requires org.slf4j;
    // Exported to test modules only.
    exports build.jenesis.repository.format.apk to
            build.jenesis.repository.format.contract.ecosystem.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.format.apk.test,
            build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.apk.ApkFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.apk.ApkListingObserver;
}
