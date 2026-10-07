/**
 * The generic (raw) repository format: a {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /raw/...} layout, a content-addressed file store over the store module's {@code Publication} primitives, with
 * a proxy leg - which relays rather than keeps the files a repository names as moving - and an importer.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.raw {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    // The action name a client's DELETE is recorded under on the audit trail.
    requires build.jenesis.repository.audit;
    // The one dial, naming the files that move, described to the settings catalogue.
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.format.raw;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.raw.RawFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.raw.RawListingObserver;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.raw.RawSettingsContributor;
}
