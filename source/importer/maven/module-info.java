/**
 * The vendor-neutral Maven import connector: an {@link build.jenesis.repository.importer.ImportSourceProvider} that
 * walks any repository serving the Maven layout over plain HTTP - Nexus, Artifactory, an httpd or nginx autoindex, a
 * static bucket - without a vendor API. A directory-listing walk where the server exposes one, else the published Nexus
 * repository index ({@code .index/nexus-maven-repository-index.gz}) with each coordinate refreshed through its
 * {@code maven-metadata.xml}. A source with neither (GitHub Packages, say) needs its vendor API, as do registry
 * formats. Discovered by {@code ServiceLoader}, so generic Maven import is present exactly when this module is.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer.maven {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.xml;
    exports build.jenesis.repository.importer.maven to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.maven.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.maven.MavenSourceProvider;
}
