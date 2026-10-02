/**
 * Download tracking: it provides {@link build.jenesis.repository.inventory.DownloadTrackerProvider} answering to
 * {@code batching}, accumulating successful reads on a bounded queue off the request path and adding them to each
 * version's document at most once per {@code download-flush-interval}, with the last download's instant - the signal
 * the {@code not-downloaded-for} retention criterion evicts by. Without this module no downloads are recorded and that
 * criterion falls back to publish age.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.downloads {
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    // BatchingWorker, which the tracker extends.
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.downloads;
    provides build.jenesis.repository.inventory.DownloadTrackerProvider
            with build.jenesis.repository.downloads.BatchingDownloadTrackerProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.downloads.DownloadSettingsContributor;
}
