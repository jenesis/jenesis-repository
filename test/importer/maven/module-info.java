/**
 * Focused unit tests for the vendor-neutral Maven import connector, driving
 * {@link build.jenesis.repository.importer.maven.MavenSource} with a fixed in-memory {@code Fetcher} that answers
 * canned autoindex pages, a canned legacy repository index and canned Maven documents - no server, no network: the
 * directory-listing walk descends depth-first in sorted order, skips metadata and checksum sidecars and navigation
 * chrome, follows a Nexus-shaped landing page's one advertised hop to its HTML index (files linked at their
 * canonical download URLs), checkpoints each completed subtree and resumes from that cursor without re-listing what
 * it skips; a
 * listing-less source falls back to the repository index (records decoded, tombstones and unsafe coordinates
 * dropped, poms implied) refreshed per coordinate through {@code maven-metadata.xml}; a source with neither fails
 * with an actionable message; and the provider answers to {@code maven}, wires credentials and cursor, and builds
 * no source over an unreachable root.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.maven
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.maven.test {
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.importer.testkit;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
