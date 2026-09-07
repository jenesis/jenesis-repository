/**
 * The Sonatype Nexus 3 import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that builds a {@code NexusSource} over the components
 * REST API, reading the listing responses with Jackson. Depends on the import SPI, the format SPI (for the shared
 * fetcher) and Jackson; the server discovers it with {@code ServiceLoader}, so Nexus support is present exactly when
 * this module is on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 * @jenesis.pin tools.jackson.databind 3.2.0
 */
module build.jenesis.repository.importer.nexus {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.nexus to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.nexus.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.nexus.NexusSourceProvider;
}
