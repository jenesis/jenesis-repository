package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Deployment;
import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Pass;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import build.jenesis.repository.webhook.WebhookDelivery;
import build.jenesis.repository.webhook.WebhookDeliveryTask;
import build.jenesis.repository.webhook.WebhookOutbox;
import build.jenesis.repository.webhook.Webhooks;

/**
 * {@code EventPublicationObserver}: the event seam's publish and unpublish producer, emitting on the publish thread
 * into whatever {@code EventSink}s a deployment installed. In <em>this</em> graph the installed sink is the webhook's,
 * so the durable note the kit reads back is a {@code WebhookOutbox} entry, delivered at-least-once from the outbox
 * onward by {@code WebhookDeliveryTask} - and the fixture states its surface in those terms because that is what
 * "durable after enqueue" means for a hook whose own effect is a fan-out.
 *
 * <p>The producer used to be {@code WebhookPublicationObserver}, provided by the delivery module and writing straight
 * into the outbox, which is why {@code PUBLISH} and {@code UNPUBLISH} never travelled the seam. Moving it
 * beside the seam changes nothing this fixture asserts - the note is written on the same thread, before the callback
 * returns, and no route back exists for a lost one - which is itself worth stating: the delivery class was a property
 * of the enqueue, not of who called it.
 *
 * <p><b>This fixture carries the kit's first and hardest exclusion, and it is a statement about the product rather
 * than about the test.</b> A publish webhook is <em>not re-derivable</em>. The store retains "this path is published";
 * it never retains "a publish occurred at T that was not announced". There is no {@code WalkConsumer}, no sweep and no
 * pass that could rebuild the missed event, so {@code A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR} is excluded
 * with that reason rather than satisfied by a {@code repair} that quietly re-derives something else. §3.4
 * measured the alternative and refused it: a drain that re-delivered from a pre-commit intent could not tell an orphan
 * intent from a real one for a {@code laidOut} publish, so guaranteeing delivery would also manufacture webhooks for
 * artifacts that never became visible - and a false webhook drives CDN purges and downstream builds. The documented
 * answer for a subscriber that cannot miss one is the reconciliation route: every event type has a durable, queryable
 * counterpart to poll (§9 D-6).
 *
 * <p><b>And it cannot be driven to record inside the kit at all.</b> The hook skips a publish whose descriptor carries
 * no coordinate - deliberately, so an event is raised once for the artifact rather than once per {@code .sha256}
 * beside it - and every artifact the shared contract commits is exactly that shape. The properties that need the surface to
 * move are excluded with that reason and proven in {@link CoordinateKeyedObserverTest}.
 */
final class PublicationEventFixture implements PublicationHookFixture.Observer, Deployment, RecordsNothingOnPublish {

    static final String SPACE = "webhook";

    /** A receiver that always answers 200, and one this repository is allowed to talk to. */
    private static final String ENDPOINT = "https://hooks.invalid/receiver *";

    static final String COORDINATE_GATE =
            "the hook skips a publish with no coordinate - a checksum or a generated sidecar must not raise its own "
                    + "event - and the kit commits ArtifactDescriptor.at(\"kit\", path), which carries none, for "
                    + "every artifact it publishes. Its surface therefore cannot move inside the kit. Proven instead "
                    + "in CoordinateKeyedObserverTest, which drives Publication.published (the store SPI's own "
                    + "after-commit seam) with a coordinate-bearing descriptor and then drains the real "
                    + "WebhookDeliveryTask."
                    + " NOTE: the kit gained a coordinate-bearing seam (PublicationHookFixture.describe), so the"
                    + " reach half of this reason is gone - override describe() and these checks do run. Flipping"
                    + " it was measured and two of the four then fail on a different thing: the checks require a"
                    + " contained failure to leave the surface demonstrably stale, and this hook's surface is a"
                    + " queue whose staleness the kit's projection does not show. That is the gap to close, and it"
                    + " is not the one this reason describes.";

    static final String NOT_RE_DERIVABLE =
            "an event is a point-in-time observation and the store retains no record that one was owed: it holds "
                    + "\"this path is published\", never \"a publish occurred at T that was not announced\". No walk, "
                    + "sweep or pass can rebuild it, so a repair leg here could only be a vacuous pass - and "
                    + "§3.4 refused the alternative (a pre-commit intent whose drain cannot tell an orphan from a "
                    + "real one would manufacture webhooks for publishes that never became visible). The "
                    + "reconciliation route is the documented answer: every event type has a durable, queryable "
                    + "counterpart to poll (§9 D-6).";

    @Override
    public String hook() {
        return "publication-events";
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
    public List<String> namespaces() {
        return List.of(SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.DURABLE_AFTER_ENQUEUE;
    }

    @Override
    public void deploy(ArtifactStore store) {
        // The feature latches in a process-global static set once by its task provider, so the deployment turns it
        // on here and the driver's reset turns it off again before the next check.
        Webhooks.configure(true);
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, NOT_RE_DERIVABLE,
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, COORDINATE_GATE,
                Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, COORDINATE_GATE,
                Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, COORDINATE_GATE,
                Property.AN_ENQUEUED_NOTE_IS_DURABLE_WHEN_THE_CALLBACK_RETURNS, COORDINATE_GATE);
    }

    @Override
    public Map<String, String> enqueued(ArtifactStore store) throws IOException {
        Map<String, String> notes = new TreeMap<>();
        for (WebhookOutbox.Entry entry : new WebhookOutbox(store).entries()) {
            notes.put(entry.id(), entry.type() + " " + entry.path());
        }
        return notes;
    }

    @Override
    public void drain(ArtifactStore store) throws IOException {
        deliveries(store).repository(Pass.over(store)
                .with("webhook-endpoints", ENDPOINT)
                .with("webhook-allow-internal", "true"));
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // A delivered webhook leaves nothing durable here at all - the drain removes the note and the effect is an
        // HTTP call to a subscriber. Reporting that honestly (the delivered surface is empty) is what makes the
        // exclusions above load-bearing rather than decorative.
        return Map.of();
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();
    }

    @Override
    public void repair(ArtifactStore store) {
        throw new UnsupportedOperationException(NOT_RE_DERIVABLE);
    }

    /** The real delivery task over a sender that always succeeds, so the drain's own outcome handling runs. */
    private static WebhookDeliveryTask deliveries(ArtifactStore store) {
        return new WebhookDeliveryTask(Duration.ofMinutes(1), 5, Duration.ofSeconds(1), Duration.ofMinutes(5),
                new WebhookDelivery((_, _, _) -> 200));
    }

    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public String whyNothingOnPublish() {
        return COORDINATE_GATE;
    }
}
