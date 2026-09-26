/**
 * The findings-and-annotations contracts: the durable, structured, per-coordinate record of what the security and
 * compliance machinery found - the shift from "recompute vulnerabilities from the feeds every time you look" to
 * "persist what was found, serve it from the store". A {@link build.jenesis.repository.findings.Finding} row keyed
 * by a repository's {@code (ecosystem, coordinate, version)} triple carries its source (which module or feed
 * produced it), kind (vulnerability, license, reachability, applicability, AI candidate, malware, gate decision),
 * category, severity, confidence, description, references, provenance and first/last-seen instants; any module
 * writes findings and labels against a coordinate through the discovered
 * {@link build.jenesis.repository.findings.FindingsProvider}, and every surface reads and filters them. The ledger
 * obeys categorize-never-discard: findings are added and labelled, never silently removed - a superseded finding is
 * marked, not erased - and a coordinate's rows are reclaimed only with its artifact (eviction, or a discarded
 * quarantine hold). With no persistence module installed {@code installed()} is empty and every writer and surface
 * degrades: nothing records, the endpoints answer that the store is absent, and the advisory surfaces fall back to
 * the live feeds.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.findings {
    // The shared ceiling the paged and streaming defaults refuse past, applied through one call.
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.compliance;
    // FindingMarks answers in the shared Mark, so a reader of this module reads the icon seam too.
    requires transitive build.jenesis.repository.icon;
    exports build.jenesis.repository.findings;
    uses build.jenesis.repository.findings.FindingsProvider;
}
