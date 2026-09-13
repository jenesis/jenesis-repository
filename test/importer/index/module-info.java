/**
 * Focused unit tests for the format-native enumeration import connector, driving
 * {@link build.jenesis.repository.importer.index.IndexSourceProvider} with a fake enumerable
 * {@link build.jenesis.repository.format.RepositoryFormat} (provided by this module and discovered through the
 * SPI's own {@code installed} lookup) and a fixed in-memory {@code Fetcher} - no server, no network: the provider
 * answers to {@code index}, requires the ecosystem format up front, builds no source for an absent or
 * non-proxying format or an unreachable root, and injects HTTP basic credentials around the shared fetcher; the
 * source streams exactly what the format enumerates under the format's own name, opens bytes lazily so a skipped
 * asset is never downloaded, checkpoints the last consumed path in batches, resumes past a cursor without
 * re-importing, restarts once when the cursor no longer appears, and surfaces an index failure mid-walk as the
 * job's failure.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.index
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.index.test {
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.importer.index.test.FakeIndexedFormat,
                 build.jenesis.repository.importer.index.test.FakeHostedFormat;
}
