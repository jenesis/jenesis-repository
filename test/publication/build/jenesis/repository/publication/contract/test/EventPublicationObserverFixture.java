package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The event seam's publish producer: every accepted publish of a coordinate is handed to every installed event sink as
 * a {@code publish} event, and a removal as an {@code unpublish}. The kit's publishes are given a coordinate - the
 * producer skips a coordinate-less artifact by design, a checksum being no event of its own - and the one sink on this
 * graph is {@link EventProbeSink}, which records what it is handed.
 */
final class EventPublicationObserverFixture implements PublicationHookFixture.Observer {

    @Override
    public String hook() {
        return "event-publication";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.events.EventPublicationObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public ArtifactDescriptor describe(String path) {
        return PublicationHookContract.coordinated(path, "kit:" + Keys.slug(path), "1.0");
    }

    @Override
    public List<String> namespaces() {
        return List.of(EventProbeSink.SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, EventProbeSink.SPACE);
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, String> events = new TreeMap<>();
        published.forEach(artifact -> events.put(Keys.slug(artifact.path()), "publish " + artifact.path()));
        return events;
    }

    @Override
    public void repair(ArtifactStore store) {
        // Declared below: nothing re-derives a notification.
    }

    @Override
    public Map<PublicationHookContract.Property, String> unsupported() {
        String atMostOnce = "a notification is at most once by the event seam's own contract - an event dropped "
                + "between the commit and the fan-out is re-derived by no walk, so a contained failure leaves the "
                + "surface stale with no route back, and a subscriber that must not miss one reconciles against the "
                + "repository rather than the feed; what a sink does with the event it is handed is that sink's";
        return Map.of(
                PublicationHookContract.Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, atMostOnce,
                PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION,
                atMostOnce,
                PublicationHookContract.Property.A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED,
                "the producer overrides no withhold leg and skips an artifact without a coordinate, and the withhold "
                        + "subject a quarantine raises carries none - so no route leads a held artifact to an event, "
                        + "and the mutation forwarding a withhold as a publish meets the coordinate gate");
    }
}
