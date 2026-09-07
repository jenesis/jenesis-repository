/**
 * The vendor-neutral Maven import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that walks <em>any</em> repository serving the Maven
 * layout over plain HTTP - Nexus, Artifactory, a plain httpd/nginx autoindex, a static bucket - without a vendor
 * API, so a migration source needs no per-incumbent adapter. Three enumeration strategies stack by availability: a
 * recursive directory-listing walk where the server exposes an autoindex, falling back to the published Nexus
 * repository index ({@code .index/nexus-maven-repository-index.gz}, read with a pure-JDK port of the jenesis-modules
 * crawler's chunk reader) for coordinates, each refreshed through its {@code maven-metadata.xml} for versions the
 * index lags behind. Honestly scoped twice over: the source must expose a listing or publish an index (one without
 * either - GitHub Packages, say - still needs its vendor API), and the walk is Maven-shaped - registry formats
 * (npm, pypi, ...) need their vendor API or format protocol. Depends only on the import SPI, the format SPI (for the shared fetcher) and
 * {@code java.xml} (for the metadata and pom documents); the server discovers it with {@code ServiceLoader}, so
 * generic-Maven support is present exactly when this module is on the path.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.importer.maven {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires java.xml;
    exports build.jenesis.repository.importer.maven to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.maven.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.maven.MavenSourceProvider;
}
