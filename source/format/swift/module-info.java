/**
 * The Swift Package Registry (SE-0292) as a plugin module: the six endpoints a {@code swift package-registry}
 * client speaks, so {@code swift package resolve} resolves from the shared store.
 *
 * <p><b>The API version is negotiated, not routed.</b> The specification puts it in the {@code Accept} header -
 * {@code application/vnd.swift.registry.v1+json}, answered with {@code Content-Version: 1} - and leaves the URL
 * space unversioned, so this format adopts no foreign {@code /vN} prefix and invents none of its own.
 *
 * <p><b>The release list is maintained, not generated.</b> It is a
 * {@link build.jenesis.repository.store.StoredListing} whose entry each publish re-decides and whose
 * {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path; a
 * {@code PublicationObserver} keeps it in step with a hold, a release, a lifecycle mark and a removal.
 *
 * <p><b>A withheld release leaves the list; a yanked one stays with a {@code problem} object</b>, which is the
 * specification's own word for a release a client must not resolve. That is a native lifecycle surface rather
 * than an absence, and the two are deliberately not the same shape.
 *
 * <p>The publish is a multipart {@code PUT} read through the shared bounded multipart reader, and the
 * {@code checksum} a client verifies against is the SHA-256 the content-addressed store computed - so it describes
 * the bytes this repository serves rather than a publisher's claim about them.
 *
 * <p>No pull-through proxy leg and no importer: there is no canonical public Swift registry to mirror, which is
 * unusual among these formats and is why those rows are declarations rather than tests.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.swift {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.multipart;
    requires org.slf4j;
    requires tools.jackson.databind;
    // Exported to test modules only; the unit suite is named here because its assertions
    // are about the path grammar, which belongs in the fastest lane.
    exports build.jenesis.repository.format.swift to
            build.jenesis.repository.format.contract.ecosystem.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.format.swift.test,
            build.jenesis.repository.compliance.swift.test,
            build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.swift.SwiftFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.swift.SwiftListingObserver;
}
