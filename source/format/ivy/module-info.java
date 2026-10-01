/**
 * An Ivy repository: {@code /ivy/...}, the layout Gradle publishes to and resolves from, with a pull-through proxy leg.
 *
 * <p>It declares the {@code Maven} ecosystem, as the Maven format does: an ecosystem name is a vulnerability database's
 * vocabulary for a coordinate space, and both address {@code org:name:revision}, so every seam mapping an ecosystem
 * back to a layout unions both. The accepted layout is restricted to Gradle's, where the organisation is the
 * {@code group}, which is what makes that claim true.
 *
 * <p>{@code ivy.xml} is not parsed: the dependency graph comes from SBOMs, so a descriptor is an artifact like any
 * other.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.ivy {
    requires build.jenesis.repository.format;
    // The proxy leg's seam and its shared relay rules.
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.format.ivy;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.ivy.IvyFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.ivy.IvyListingObserver;
}
