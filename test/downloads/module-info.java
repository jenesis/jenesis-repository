/**
 * The download-tracking plugin in isolation - the {@code batching} {@link
 * build.jenesis.repository.inventory.DownloadTrackerProvider} that accumulates successful reads off the request path
 * and writes each coordinate's last-downloaded marker into the repository inventory at most once per day, the signal
 * the {@code not-downloaded-for} retention criterion evicts by. Everything runs through the store SPI over a real
 * filesystem {@link build.jenesis.repository.store.ArtifactStore} (the {@code FaultInjectingStore} decorator counting
 * the compare-and-set marker writes), driven synchronously through the tracker's public {@code drain} so the
 * at-most-once-per-day coalescing is asserted deterministically against an injected {@code Instant} clock rather than
 * a wall clock or the worker thread. Three behaviours are pinned. First, {@code BatchingDownloadTracker}: an
 * accumulated read persists the last-downloaded marker into the inventory and reads back; repeated reads of the same
 * coordinate within a UTC day coalesce to a single store write, its marker timestamp unchurned; a new day writes a
 * fresh marker and the in-memory day map stays bounded across the day boundary; a batch's duplicate hits coalesce; the
 * bounded queue drops under a burst rather than blocking or growing unboundedly; and the off-request-path worker drains
 * a recorded hit to the store. Second, {@code BatchingDownloadTrackerProvider}: {@link java.util.ServiceLoader}
 * discovers it, it answers to the {@code batching} name, and {@code resolve} yields an enabled or disabled tracker as
 * the {@code track-downloads} dial says. Third, {@code DownloadSettingsContributor}: it declares the {@code
 * track-downloads} gate setting, discovered as a {@link build.jenesis.repository.settings.SettingsContributor}. No
 * network and no framework.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.downloads
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.downloads.test {
    requires build.jenesis.repository.downloads;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.server.spi;   // drain() and dropped() are declared on BatchingWorker
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.format;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.inventory.DownloadTrackerProvider;
    uses build.jenesis.repository.settings.SettingsContributor;
}
