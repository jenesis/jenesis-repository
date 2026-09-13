/**
 * Focused unit tests for the Artifactory import connector, driving
 * {@link build.jenesis.repository.importer.artifactory.ArtifactorySource} with a fixed in-memory {@code Fetcher} that
 * answers the storage listing API from canned JSON - no Artifactory, no network: the walk lists a repository's files,
 * skips folder entries, reports the repository's single ecosystem format for every asset with one terminal checkpoint,
 * streams a download lazily, sends basic credentials, and surfaces a failed listing as an {@code IOException}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.artifactory
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.artifactory.test {
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
