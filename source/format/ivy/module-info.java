/**
 * An Ivy repository as a plugin module: {@code /ivy/...}, the layout Gradle publishes to and resolves from.
 *
 * <p><b>It declares the {@code Maven} ecosystem, and is the second format to do so.</b> That is the design rather
 * than a collision: an ecosystem name is the vocabulary a vulnerability database uses for a coordinate space, and
 * an Ivy repository and a Maven one both address {@code org:name:revision}. Every seam that maps an ecosystem back
 * to a layout asks each format that declares it and unions the answers, so a coordinate published both ways is one
 * coordinate served two ways rather than two rows that never meet.
 *
 * <p><b>Why the accepted layout is restricted, which is the load-bearing half.</b> Ivy's layout is a pattern and
 * its {@code organisation} is not generally a Maven {@code groupId}, so a format accepting any pattern could not
 * honestly claim that coordinate space - its coordinates would match no advisory while asserting they should.
 * Gradle writes the {@code group} as the organisation, so restricting the layout is exactly what makes the claim
 * true, and the claim is what buys advisory matching, retention and the browse.
 *
 * <p>No parser for {@code ivy.xml}, and that is not a gap: this product builds its dependency graph from SBOM
 * documents rather than from format descriptors, so a descriptor here is an artifact like any other.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.ivy {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.format.ivy to
            build.jenesis.repository.format.contract.ecosystem.test,
            build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.ivy.IvyFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.ivy.IvyListingObserver;
}
