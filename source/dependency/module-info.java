/**
 * The dependency-graph primitive: it parses the CycloneDX SBOM a Jenesis build embeds in a stored artifact into a
 * format-neutral {@link build.jenesis.repository.dependency.DependencyGraph} of components and edges - the model a
 * {@code Lease}-guarded sweep folds into a sharded reverse-dependency index and a "who depends on X" / CVE
 * blast-radius query reads back. Deliberately a thin, store-agnostic library: it operates on streams (the caller
 * opens the blob through the store SPI and hands the bytes over), holds no persistence, and depends only on the
 * JDK's XML reader and the Jackson databind already on the server path - a library for each serialisation rather
 * than a hand-rolled parser. Reused across the inventory and analysis modules, so it lives on its own.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependency {
    requires build.jenesis.repository.store;
    requires com.github.benmanes.caffeine;
    requires java.xml;
    requires tools.jackson.databind;
    exports build.jenesis.repository.dependency;
}
