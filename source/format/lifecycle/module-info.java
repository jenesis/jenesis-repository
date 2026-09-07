/**
 * The version-lifecycle flag a format surfaces natively - an operator's mark that a hosted version is
 * {@code deprecated} or {@code yanked}, kept as a small per-tenant metadata object through the {@code ArtifactStore}
 * abstraction (never a raw file). A format reads the flag for a coordinate/version at serve time and translates it to
 * its own native signal (npm's {@code deprecated} string, Cargo's {@code yanked} boolean, ...), and the operator
 * endpoint writes and clears it through the same store-key convention this module owns. A thin, dependency-minimal
 * helper over the store SPI; pure JDK, so the language format plugins can {@code requires} it without pulling in a
 * peer format or the server.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.lifecycle {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.format.lifecycle;
}
