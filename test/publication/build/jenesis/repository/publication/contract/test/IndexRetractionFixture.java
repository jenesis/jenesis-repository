package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.index.PublishedIndex;
import build.jenesis.repository.index.PublishedIndexTask;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code IndexRetractionObserver}: the published index's subscription to the withhold-change feed - one coalescing
 * retraction flag, repaired (and consumed) by {@code PublishedIndexTask}'s forced rebase.
 *
 * <p><b>It is the one shipped observer with no publish leg at all.</b> {@code onPublished} is an explicit no-op - a
 * publish is not a withhold transition, and the index's own append pass already screens new paths through the
 * servable-name seam - so the only legs it declares are {@code onWithheld} and {@code onWithholdCleared}. The kit's
 * after-commit contract declares convergence over a list of <em>published</em> artifacts, so for this hook that list
 * converges onto an empty surface however many artifacts are on it, and the four properties whose assertion needs a
 * publish to move the surface are excluded here and proven in {@link CoordinateKeyedObserverTest} over the withhold
 * transitions this hook actually rides. {@code THE_WITHHOLD_FEED_FIRES_ONLY_ON_A_DURABLE_TRANSITION} is the property
 * this hook is the archetype for, and it runs.
 */
final class IndexRetractionFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    /** {@code PublishedIndex.PREFIX}; the constant is package-private, so the key is named rather than imported. */
    static final String SPACE = "index/publish";

    private static final String NO_PUBLISH_LEG =
            "the hook declares no publish leg: onPublished is an explicit no-op, and its surface is a coalescing "
                    + "retraction flag driven only by onWithheld / onWithholdCleared. The kit declares convergence "
                    + "over a list of PUBLISHED artifacts, so this surface cannot move inside a publish-driven check "
                    + "at all - and must not, or the flag would force an index rebase on every upload. Proven instead "
                    + "in CoordinateKeyedObserverTest, over the withhold transitions the hook rides, including the "
                    + "PublishedIndexTask rebase that consumes the flag.";

    @Override
    public String hook() {
        return "index-retraction";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.index.IndexRetractionObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, NO_PUBLISH_LEG,
                Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, NO_PUBLISH_LEG,
                Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, NO_PUBLISH_LEG,
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, NO_PUBLISH_LEG);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // Presence is the whole signal - the flag's body is the transition's wall-clock instant, which two converged
        // runs legitimately differ on - so the projection keeps the fact and drops the stamp.
        return new PublishedIndex(store).retraction().peek().isPresent()
                ? Map.of("retraction", "flagged")
                : Map.of();
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();   // a publish is not a withhold transition: the retraction flag must stay unset
    }

    /**
     * A withhold IS this hook's transition, so unlike almost every other fixture it holds durable state for a held
     * artifact - the retraction flag, which is exactly what {@code onWithheld} exists to set.
     *
     * <p>Declared because the check now compares the surface WHOLE rather than probing it for publish-row keys. That
     * older shape could not see this hook at all: its publish row is empty, so there were no keys to probe for, and
     * a flag it set on the withhold leg went unasserted either way. Writing it down is the coverage - the flag is
     * now pinned as what a QUARANTINE must leave behind, rather than merely not being the publish row.
     */
    @Override
    public Map<String, String> withheld(List<ArtifactDescriptor> withheld) {
        return Map.of("retraction", "flagged");
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        // The concrete repair: PublishedIndexTask's rebase, what a walk carrying IndexRebaseConsumer runs at its
        // completion - re-screens every path through the servable-name seam and, having committed, clears the flag
        // under its own token.
        new PublishedIndexTask(Duration.ofDays(1), 8L * 1024 * 1024).rebase(store, Instant.now());
    }

    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public String whyNothingOnPublish() {
        return NO_PUBLISH_LEG;
    }
}
