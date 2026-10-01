/**
 * The retention contracts: a policy (keep-last, max-age, prerelease expiry, not-downloaded-for) decides which published
 * versions to evict; a {@code RetentionSweeper} computes a plan that can be previewed, then applies it through a
 * {@code RepositoryInventory}. Storage-agnostic: the store-backed inventory lives in its own module, and the sweep
 * engine is a discovered {@code RetentionProvider} - a deployment without one runs without retention.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cleanup {
    // The shared ceiling the grouped-stream default refuses past.
    requires build.jenesis.repository.bounds;
    requires build.jenesis.repository.settings;
    // The store contract, for the shared Providers resolution.
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.cleanup;
    uses build.jenesis.repository.cleanup.RetentionProvider;
    uses build.jenesis.repository.cleanup.VersionRemoval;
}
