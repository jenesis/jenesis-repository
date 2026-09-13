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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
