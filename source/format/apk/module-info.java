/**
 * The Alpine {@code apk} repository as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the plain HTTP layout {@code apk} speaks, so
 * {@code apk update} and {@code apk add} resolve packages over the shared store.
 *
 * <p>It owns {@code /apk/<repo>/<arch>/...}: the index at {@code APKINDEX.tar.gz}, a package at
 * {@code <name>-<version>.apk}, and a publish as {@code PUT} of the raw {@code .apk} to that same path - the path
 * {@code abuild} produces and the one an operator's own upload script writes to.
 *
 * <p><b>The index is maintained, not generated.</b> {@code APKINDEX} is a
 * {@link build.jenesis.repository.store.StoredListing} whose entry each publish re-decides and whose
 * {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path; the
 * {@code APKINDEX.tar.gz} a client downloads is its derived twin, ordered by the document's own sequence so the
 * archive never predates the index it was built from. A {@code PublicationObserver} keeps the document in step with
 * a hold, a release, a lifecycle mark and a removal.
 *
 * <p><b>Every indexed field is derived from the package, never supplied beside it.</b> The publish streams the
 * {@code .apk} into the content-addressed store, reopens a bounded prefix of the stored blob, and reads the
 * {@code .PKGINFO} out of its control segment - so a publisher cannot describe an artifact as something other than
 * what will be served. A package whose {@code pkgname}/{@code pkgver} disagree with the path it was deployed to is
 * refused, because the path is what a hold, a scan verdict and a licence screen name it by.
 *
 * <p><b>Signed, with the repository's own key.</b> Alpine verifies {@code APKINDEX.tar.gz} against a trusted RSA
 * public key, and reaches an unsigned repository only with {@code --allow-untrusted}, which switches verification
 * off for every repository that client uses. So the first publish generates an RSA key pair, every derived archive
 * carries a {@code .SIGN.RSA256.} member, and the public half is served at {@code GET /apk/keys/jenesis.rsa.pub}
 * for an operator to place in {@code /etc/apk/keys/}. See {@code ApkSigner} for the scheme and its measurements.
 *
 * <p>It also provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "Alpine"}
 * ecosystem, and {@code BlobLayout}, which is the coordinate seam an eviction and a compliance read follow - an apk
 * pointer lives in the blobs namespace rather than under {@code publish/}.
 *
 * <p>No pull-through proxy leg and no importer: each is a separate change, and neither is implied by "serve the
 * repository".
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
    requires org.apache.commons.compress;
    requires org.slf4j;
    // Exported to test modules only; the unit suite is named here because its assertions
    // are about the path grammar, which belongs in the fastest lane.
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
