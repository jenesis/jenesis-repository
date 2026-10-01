/**
 * The staging and promotion contracts: a deploy lands in a staging repository that can be reviewed (the compliance gate
 * is the natural reviewer) and is then promoted into the release layout or dropped. The store is content-addressed, so
 * promotion re-points the same blobs and a drop never disturbs a release. {@code StagingRepository} over a
 * {@code StagingBackend} models the transitions; the production operations ({@code Staging}) come from a discovered
 * {@code StagingProvider}, without which a deployment runs without staging.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.staging {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.staging;
    uses build.jenesis.repository.staging.StagingProvider;
}
