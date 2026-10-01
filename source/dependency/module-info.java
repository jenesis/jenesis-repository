/**
 * The dependency-graph primitive: it parses the SBOM embedded in a stored artifact (CycloneDX or SPDX) into a
 * format-neutral {@link build.jenesis.repository.dependency.DependencyGraph} of components and edges, which a
 * lease-guarded sweep folds into the reverse-dependency index. A store-agnostic library over streams, holding no
 * persistence, reused by the inventory and analysis modules.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependency {
    requires build.jenesis.repository.store;
    requires com.github.benmanes.caffeine;
    requires build.jenesis.repository.xml;
    requires tools.jackson.databind;
    exports build.jenesis.repository.dependency;
}
