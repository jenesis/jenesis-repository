/**
 * Focused unit tests for the Nexus 3 import connector, driving {@link build.jenesis.repository.importer.nexus.NexusSource}
 * with a fixed in-memory {@code Fetcher} that answers the components REST API from canned JSON - no Nexus, no network:
 * the walk pages by continuation token, reports each asset with its per-component format and a resume cursor, streams a
 * download lazily, sends basic credentials as an {@code Authorization} header, and surfaces a failed listing as an
 * {@code IOException}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.nexus
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.importer.nexus.test {
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
