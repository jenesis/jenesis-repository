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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.format.lifecycle {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.format.lifecycle;
}
