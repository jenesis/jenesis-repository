/**
 * Download tracking as a plugin module: it provides
 * {@link build.jenesis.repository.inventory.DownloadTrackerProvider} answering to {@code batching}, accumulating
 * successful reads on a bounded queue off the request path and writing each coordinate's last-downloaded marker
 * into the repository inventory at most once per day - the signal the {@code not-downloaded-for} retention
 * criterion evicts by. A deployment without this module records no downloads (and that criterion falls back to
 * publish age); its settings ride along, so the tracking dial is listed exactly when the module is installed.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.downloads {
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    // The tracker extends BatchingWorker, which lives beside the KeyUsageTracker seam it was
    // extracted with; inventory does not re-export server.spi, so this module reads it itself.
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.downloads to build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.downloads.test;
    provides build.jenesis.repository.inventory.DownloadTrackerProvider
            with build.jenesis.repository.downloads.BatchingDownloadTrackerProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.downloads.DownloadSettingsContributor;
}
