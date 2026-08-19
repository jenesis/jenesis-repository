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
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.lifecycle {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.format.lifecycle;
}
