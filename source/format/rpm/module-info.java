/**
 * The RPM/yum format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /rpm/...} layout - a streaming {@code .rpm} upload ({@code PUT /rpm/<repo>/<path>/<file>.rpm}, the raw package
 * as the body), the {@code repodata} ({@code repomd.xml} + {@code primary.xml[.gz]}) maintained on the publish from a
 * precomputed per-package stanza (each stanza and the {@code repomd.xml} written with the JDK StAX
 * {@code XMLStreamWriter}, so header text is escaped by the writer), and the package downloads. The RPM header at the
 * front of an upload is the only part
 * materialised (a bounded metadata parse); the cpio payload streams straight into the content-addressed store. It is
 * also a {@link build.jenesis.repository.format.ProxyFormat}, pull-through mirroring an upstream yum repository (an
 * immutable {@code .rpm} streamed into the CAS and cached, the mutable {@code repodata} streamed through fresh). It also
 * provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "RPM"} ecosystem and resolving a
 * {@code .rpm} path to its NEVRA coordinate. Finally it provides a {@link build.jenesis.repository.format.RepositoryImporter}
 * that migrates a Nexus/Artifactory {@code yum} repository, streaming each {@code .rpm} into the CAS as a hosted publish.
 * The RPM header structure is read directly (no modular RPM library fitting the module-path / native-image /
 * permissive-licence constraints exists); the only external dependency is Bouncy Castle ({@code org.bouncycastle.pg}),
 * used to OpenPGP-sign the {@code repomd.xml} metadata ({@code repomd.xml.asc}) since the JDK has no OpenPGP, reusing
 * the same detached-signature pattern as the Debian {@code Release.gpg}. Discovered through {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.rpm {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.format.signing;
    requires java.xml;
    requires org.slf4j;
    exports build.jenesis.repository.format.rpm to
            build.jenesis.repository.gateway.rpm.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.rpm.RpmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.rpm.RpmListingObserver;
}
