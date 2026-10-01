/**
 * The findings-and-annotations contracts: the durable per-coordinate record of what the security and compliance
 * machinery found, served from the store rather than recomputed from the feeds. A
 * {@link build.jenesis.repository.findings.Finding} keyed by {@code (ecosystem, coordinate, version)} is written and
 * labelled through the discovered {@link build.jenesis.repository.findings.FindingsProvider} and read and filtered by
 * every surface; rows are categorized, never discarded, and go only with their artifact.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.findings {
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.compliance;
    // Transitive: FindingMarks answers in the shared Mark.
    requires transitive build.jenesis.repository.icon;
    exports build.jenesis.repository.findings;
    uses build.jenesis.repository.findings.FindingsProvider;
}
