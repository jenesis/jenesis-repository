/**
 * The staging and promotion contracts for the repository. A deploy lands in a staging repository that
 * can be reviewed (the compliance gate is the natural reviewer) and then promoted into the release layout or
 * dropped. Because the store is content-addressed, promotion is just re-pointing the same blobs, so it is cheap and
 * a drop never disturbs a release. The in-memory lifecycle model ({@code StagingRepository} over a
 * {@code StagingBackend}) pins the transitions down in a unit test; the production operations surface
 * ({@code Staging}) is supplied by a {@code StagingProvider} module discovered with {@code ServiceLoader}, so a
 * deployment without one simply runs without staging.
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
