/**
 * Focused unit tests for the import-source SPI's own types: the value type
 * {@link build.jenesis.repository.importer.ImportRequest}, whose two required fields default the optional ones to null
 * and whose each {@code with...} returns an independent copy that changes one field while preserving the rest; and
 * {@link build.jenesis.repository.importer.ImportScreen}, the one screen a migration's outbound requests pass - the
 * edge shape that judges the URL an operator submitted under the {@code block-private-import-hosts} dial, and the
 * fetch shape that judges every URL a source hands back against the URL that dial admitted. The structural ratchet
 * that no edge builds a source through the unscreened seam is {@code ImportFetchScreenGuardTest}, which lives in
 * {@code test/server} because that is where an edge would appear.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.test {
    requires build.jenesis.repository.importer;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
