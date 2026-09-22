/**
 * The retention contracts for the repository. A retention policy (keep-last, max-age, prerelease
 * expiry, not-downloaded-for) decides which published versions to evict; a {@code RetentionSweeper} computes a plan
 * that can be previewed before anything is deleted, then applies it through a {@code RepositoryInventory}. Pure JDK
 * and storage-agnostic: the policy is unit-tested over a list, the artifact-store-backed inventory lives in its own
 * module, and the sweep engine is supplied by a {@code RetentionProvider} module discovered with
 * {@code ServiceLoader} - a deployment without one simply runs without retention.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cleanup {
    // The shared ceiling the grouped-stream default refuses past (the earlier ruling, one call).
    requires build.jenesis.repository.bounds;
    requires build.jenesis.repository.settings;
    // The java.base-light store contract, for the shared Providers resolution mechanism.
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.cleanup;
    uses build.jenesis.repository.cleanup.RetentionProvider;
}
