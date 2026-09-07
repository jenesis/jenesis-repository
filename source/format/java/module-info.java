/**
 * The shared Java repository-layout primitives the Maven and Jenesis layout formats build on: reading a jar's module
 * name and parsing a Maven request path ({@link build.jenesis.repository.format.java.JavaLayout}). It also carries the
 * cross-publish bridge ({@link build.jenesis.repository.format.java.bridge}) - the {@code ModuleView} contract by which
 * the Maven layout hands a published modular jar to the Jenesis layout for its module view - exported only to those two
 * modules, so cross-publishing stays off the public {@code RepositoryFormat} SPI. Neither the SPI nor any other format
 * sees the bridge.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.java {
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    uses build.jenesis.repository.format.java.bridge.ModuleView;
    exports build.jenesis.repository.format.java;
    exports build.jenesis.repository.format.java.bridge
            to build.jenesis.repository.format.maven, build.jenesis.repository.format.jenesis;
}
