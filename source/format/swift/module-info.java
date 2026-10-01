/**
 * The Swift Package Registry (SE-0292): the six endpoints a {@code swift package-registry} client speaks, a
 * pull-through proxy leg to an upstream registry, and an importer for Artifactory's layout.
 *
 * <p>The API version is negotiated in {@code Accept}, so this format adopts no foreign {@code /vN} prefix and invents
 * none.
 *
 * <p>The release list is a {@link build.jenesis.repository.store.StoredListing} each publish re-decides an entry of,
 * whose {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path; a
 * {@code PublicationObserver} keeps it in step with holds, releases, lifecycle marks and removals. A withheld release
 * leaves the list; a yanked one stays with a {@code problem} object.
 *
 * <p>The publish is a multipart {@code PUT} through the shared bounded multipart reader, and the {@code checksum} a
 * client verifies is the SHA-256 the store computed.
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
    // Exported for the suites that drive the format directly.
    exports build.jenesis.repository.format.swift;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.swift.SwiftFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.swift.SwiftListingObserver;
}
