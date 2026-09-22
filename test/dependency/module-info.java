/**
 * Tests of the dependency-graph primitive in isolation: the CycloneDX parser (JSON and XML, the two serialisations
 * the Jenesis emitter writes), the coordinate/purl model, and the embedded-SBOM extractor that reads a jar without
 * buffering it. No network and no framework - the graph model is pinned to the reference CycloneDX shape before the
 * reverse-dependency sweep and query build on it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.dependency
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependency.test {
    requires build.jenesis.repository.dependency;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
