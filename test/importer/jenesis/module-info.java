/**
 * Focused unit tests for the jenesis-to-jenesis import connector, driving
 * {@link build.jenesis.repository.importer.jenesis.JenesisSource} with a fixed in-memory {@code Fetcher} that
 * answers the {@code /api/assets} enumeration from canned JSON - no server, no network: the walk pages by the
 * response cursor, reports each asset with its format and the layout-relative path the importer expects (the
 * {@code /<format>/} serving prefix stripped), streams a download lazily, sends the API key in the
 * {@code Jenesis-Repository-Key} header, and surfaces a failed listing as an {@code IOException}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.jenesis
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.jenesis.test {
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
