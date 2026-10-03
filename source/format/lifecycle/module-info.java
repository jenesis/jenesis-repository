/**
 * The version-lifecycle flag a format surfaces natively - a mark that a hosted version is {@code deprecated} or
 * {@code yanked}, kept as a small per-tenant object in the store. A format reads it at serve time and renders its
 * native signal (npm's {@code deprecated} string, Cargo's {@code yanked} boolean); the operator endpoint writes and
 * clears it through the same key convention this module owns. Pure JDK over the store SPI, so a format can require it
 * without a peer format or the server.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.lifecycle {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires transitive build.jenesis.repository.format;
    requires build.jenesis.repository.audit;
    exports build.jenesis.repository.format.lifecycle;
}
