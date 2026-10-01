/**
 * The generic (raw) repository format: a {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /raw/...} layout, a content-addressed file store over the store module's {@code Publication} primitives, with
 * a proxy leg and an importer.
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
    exports build.jenesis.repository.format.raw;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.raw.RawFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.raw.RawListingObserver;
}
