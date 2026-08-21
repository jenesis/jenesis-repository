package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Delivery;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Observer;

import static build.jenesis.repository.store.testkit.PublicationHookContract.BODY;
import static build.jenesis.repository.store.testkit.PublicationHookContract.commit;
import static build.jenesis.repository.store.testkit.PublicationHookContract.descriptor;
import static build.jenesis.repository.store.testkit.PublicationHookContract.equal;
import static build.jenesis.repository.store.testkit.PublicationHookContract.failure;
import static build.jenesis.repository.store.testkit.PublicationHookContract.hash;
import static build.jenesis.repository.store.testkit.PublicationHookContract.isTrue;
import static build.jenesis.repository.store.testkit.PublicationHookContract.publication;
import static build.jenesis.repository.store.testkit.PublicationHookContract.thrownBy;

/**
 * The after-commit half of the publication-hook contract: the eleven {@link PublicationObserver} clauses, driven
 * through {@link Publication#commit} - the one hosted-publish choreography - rather than through a hand-assembled
 * sequence.
 *
 * <p>Two things separate this from the verdict half and are asserted rather than assumed. A failure here is
 * <b>contained</b>: the publish stands, the removal stands, and the later observers still run. And the delivery is
 * <b>lossy</b> across the mutation-to-callback window whatever the callback writes once invoked - so the kit crashes
 * exactly there, proves the artifact serves while the surface never heard of it, and then <em>runs</em> the fixture's
 * repair leg. "There is a walk that heals this" is an executed fact here, not a sentence in a comment.
 */
final class AfterCommitContract {

    private AfterCommitContract() {
    }

    static void checks(List<PublicationHookContract.Check> checks) {
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION,
                "a throwing observer is contained, the mutation stands, and the loss is visible in durable state",
                AfterCommitContract::aThrowingObserverIsContained));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.AN_ERROR_ESCAPES_THE_OBSERVER_CONTAINMENT,
                "an Error escapes the containment even though an IOException does not",
                AfterCommitContract::anErrorEscapesTheContainment));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_DUPLICATE_DELIVERY_CONVERGES,
                "a byte-identical re-publish notifies again and the surface upserts rather than doubling",
                AfterCommitContract::aDuplicateDeliveryConverges));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE,
                "the derived state lands under the tenant/repository scope the artifact was published into",
                AfterCommitContract::theObserverRecordsThroughThePublishedScope));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD,
                "a lost call leaves a stale surface but hides neither a served artifact nor a hold",
                AfterCommitContract::aLostCallHidesNothing));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL,
                "the crash between the visibility write and the callback loses the call for every declared class",
                AfterCommitContract::theCommitToCallbackWindowLosesTheCall));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR,
                "the fixture's own repair leg runs over durable truth and converges the surface",
                AfterCommitContract::aDroppedCallIsHealedByAnExecutableRepair));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.AN_ENQUEUED_NOTE_IS_DURABLE_WHEN_THE_CALLBACK_RETURNS,
                "the note is durable the moment the callback returns, before any drain has run",
                AfterCommitContract::anEnqueuedNoteIsDurableWhenTheCallbackReturns));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_REPEATED_DRAIN_LEAVES_THE_SAME_SURFACE,
                "draining the same notes twice leaves the same surface",
                AfterCommitContract::aRepeatedDrainLeavesTheSameSurface));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED,
                "a quarantined or rejected screen never reaches an after-commit observer",
                AfterCommitContract::aQuarantinedOrRejectedPublishIsNeverObserved));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.THE_WITHHOLD_FEED_FIRES_ONLY_ON_A_DURABLE_TRANSITION,
                "the withhold feed fires at the durable transitions and only on an actual transition",
                AfterCommitContract::theWithholdFeedFiresOnlyOnATransition));
    }

    // --- clause 7: containment, and the trace it must leave --------------------------------------------------------

    private static void aThrowingObserverIsContained(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/contained");

        // (a) a probe that always throws sits AHEAD of the fixture's observer, so containment is exercised even if
        //     the implementation happens to swallow its own failures, and "later observers still run" is asserted.
        List<String> reached = new ArrayList<>();
        Publication publication = publication(store, List.of(
                throwing(new IOException("remote target down")), observer.create(),
                (PublicationObserver) (published, _) -> reached.add(published.path())));

        Publication.Commit committed = commit(publication, artifact);

        isTrue(committed.visible(), fixture, "a throwing observer never fails the publish it observed");
        equal(publication.located(artifact.path()).isPresent(), true, fixture,
                "and never unlinks the artifact - the failure has no say in a disposition already reached");
        equal(reached, List.of(artifact.path()), fixture, "the observers after the throwing one still run");
        settle(observer, store);
        equal(observer.projection(store), observer.converged(List.of(artifact)), fixture,
                "the fixture's own observer, sitting behind the throwing probe, recorded the publish");

        // (b) now the fixture's OWN observer fails, by faulting exactly the keys it writes. The publish must still
        //     stand - and, per the plan's gate 4, the failure must leave a TRACE: a contained failure that is
        //     indistinguishable from a successful one is the defect, not the containment. The trace here is durable
        //     divergence plus a route back, and both are asserted from the store rather than from a log line.
        ArtifactDescriptor second = descriptor("/kit/contained-own");
        for (String space : observer.namespaces()) {
            Predicate<String> keys = FaultInjectingStore.keyPrefix(space);
            store.failEveryOn(FaultInjectingStore.Op.WRITE, keys);
            store.failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, keys);
            store.failEveryOn(FaultInjectingStore.Op.DELETE, keys);
        }
        Publication.Commit blind = commit(publication(store, List.of(observer.create())), second);
        store.heal();

        isTrue(blind.visible(), fixture, "the observer's own store failure is contained exactly like the probe's");
        equal(publication.located(second.path()).isPresent(), true, fixture, "and the artifact serves");
        settle(observer, store);
        Map<String, String> stale = observer.projection(store);
        Map<String, String> whole = observer.converged(List.of(artifact, second));
        isTrue(!stale.equals(whole), fixture,
                "a contained failure must be VISIBLE somewhere: the surface is expected to be demonstrably stale "
                        + "after one, so an operator - or the repair sweep - can tell the two apart. This surface "
                        + "recorded the publish anyway, which means the failure left no trace at all (gate 4).");
        observer.repair(store);
        settle(observer, store);
        equal(observer.projection(store), whole, fixture,
                "and there is a route back: the repair leg converges the surface the contained failure left stale");
    }

    private static void anErrorEscapesTheContainment(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/error");
        Publication publication = publication(store, List.of(
                (PublicationObserver) (_, _) -> {
                    throw new StackOverflowError("an observer that blew the stack");
                }, observer.create()));

        Throwable failure = thrownBy(() -> commit(publication, artifact));

        isTrue(failure instanceof StackOverflowError, fixture,
                "Publication contains `Exception`, not `Throwable`, so an Error out of an observer escapes and fails "
                        + "the publish - a fact only the code states, and one a kit arming its probes with "
                        + "RuntimeException alone would never notice (was " + failure + ")");
        // ... and the escape happens AFTER the commit point, so the artifact serves although the caller was told the
        // publish failed. That asymmetry is the documented crash window made synchronous, and it is worth pinning.
        equal(publication.located(artifact.path()).isPresent(), true, fixture,
                "the visibility write landed before the escaping Error, so the artifact serves although the caller "
                        + "saw a failure");
    }

    // --- clause 2: replay ------------------------------------------------------------------------------------------

    private static void aDuplicateDeliveryConverges(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/replayed");
        Publication publication = publication(store, List.of(observer.create()));

        commit(publication, artifact);
        settle(observer, store);
        Map<String, String> once = observer.projection(store);
        equal(once, observer.converged(List.of(artifact)), fixture, "the first delivery converged");

        // The replay that repairs a first attempt which crashed mid-layout: identical bytes, the whole choreography
        // again, a fresh observer instance because a restarted process keeps no memory.
        commit(publication(store, List.of(observer.create())), artifact);
        settle(observer, store);

        equal(observer.projection(store), once, fixture,
                "a duplicate delivery leaves the surface exactly as one delivery did - upsert, never blind-append or "
                        + "blind-increment on a correctness-bearing row");
    }

    // --- clause 6: tenant scoping ----------------------------------------------------------------------------------

    private static void theObserverRecordsThroughThePublishedScope(PublicationHookFixture fixture,
                                                                   FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactStore scoped = store.scope("acme").scope("main");
        ArtifactDescriptor artifact = descriptor("/kit/scoped");

        commit(publication(scoped, List.of(observer.create())), artifact);
        settle(observer, scoped);

        equal(observer.projection(scoped), observer.converged(List.of(artifact)), fixture,
                "the follow-up note lands under exactly the tenant/repository space the artifact did");
        equal(observer.projection(store), Map.of(), fixture,
                "and nothing was recorded against the unscoped store - a derived row written one scope up is a row "
                        + "recorded for the wrong repository");
    }

    // --- clause 7's blast radius, and clause 11's crash window -----------------------------------------------------

    private static void aLostCallHidesNothing(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/lost");
        Publication publication = publication(store, List.of(observer.create()));

        // The documented window: the declared visibility write lands and the caller never learns it did, so the
        // artifact serves and no observer was ever notified.
        store.crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
        Throwable failed = thrownBy(() -> commit(publication, artifact));
        store.heal();
        isTrue(failed != null, fixture, "the injected crash must fail the commit, or this check kills nothing");

        // Re-derived from durable state, per the plan's gate 5: the pointer really landed, which is what makes this
        // the lost-callback window rather than a plain failed publish.
        equal(store.delegate().readVersioned("publish" + artifact.path()).isPresent(), true, fixture,
                "the visibility write landed before the crash - if it did not, this crash point has stopped biting "
                        + "and every claim below it is vacuous");
        equal(publication.located(artifact.path()).isPresent(), true, fixture, "so the artifact serves");
        equal(new ServableNames(store, publication).state(artifact.path()), ServableNames.State.SERVABLE, fixture,
                "and the enumeration seam agrees it serves - the observer's ignorance cannot hide it");
        settle(observer, store);
        isTrue(!observer.projection(store).equals(observer.converged(List.of(artifact))), fixture,
                "while the derived surface never heard of it: that is the blast radius, and it is bounded to the "
                        + "observer's own state");

        // The other half of clause 7's promise: a lost call can never hide a HOLD either. A held path stays held
        // whatever an observer of the hold did or did not manage to record.
        ArtifactDescriptor held = descriptor("/kit/held");
        Publication holding = publication(store, List.of(throwing(new IOException("feed consumer down")),
                observer.create()));
        commit(holding, held);
        holding.link("/quarantine" + held.path(), hash(BODY));
        equal(new ServableNames(store, publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals(held.path());
            }
        }))).state(held.path()), ServableNames.State.WITHHELD, fixture,
                "the hold stands although the withhold-feed observer threw: an after-commit observer may over-serve "
                        + "or over-count on its own surface, never hide a served artifact or a hold");
    }

    private static void theCommitToCallbackWindowLosesTheCall(PublicationHookFixture fixture,
                                                              FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/window");

        store.crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
        Throwable failed = thrownBy(() -> commit(publication(store, List.of(observer.create())), artifact));
        store.heal();
        isTrue(failed != null, fixture, "the injected crash must fail the commit");
        equal(store.delegate().readVersioned("publish" + artifact.path()).isPresent(), true, fixture,
                "the crash landed after the visibility write - re-derived from durable state, not assumed");

        // Whatever the callback would have written, it was never called. That is why no class stronger than
        // durable-after-enqueue is available on this seam: the outbox lives INSIDE the callback.
        equal(observer.enqueued(store), Map.of(), fixture,
                "nothing was enqueued, because the callback never ran - writing an outbox inside an after-commit "
                        + "callback makes what WAS delivered durable, it does not close the window before it");
        settle(observer, store);
        isTrue(!observer.projection(store).equals(observer.converged(List.of(artifact))), fixture,
                "and nothing was delivered. A fixture may not declare " + Delivery.COMMIT_COUPLED_AT_LEAST_ONCE
                        + " while this holds; only the earlier pre-commit intent machine could change it.");
    }

    private static void aDroppedCallIsHealedByAnExecutableRepair(PublicationHookFixture fixture,
                                                                 FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor seen = descriptor("/kit/repaired-seen");
        ArtifactDescriptor missed = descriptor("/kit/repaired-missed");
        Publication publication = publication(store, List.of(observer.create()));

        commit(publication, seen);
        store.crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED,
                FaultInjectingStore.keyContaining("repaired-missed"));
        isTrue(thrownBy(() -> commit(publication, missed)) != null, fixture, "the second publish crashes past its "
                + "visibility write");
        store.heal();
        settle(observer, store);
        Map<String, String> whole = observer.converged(List.of(seen, missed));
        isTrue(!observer.projection(store).equals(whole), fixture,
                "the surface really is missing the dropped publish, or the repair below proves nothing");

        // The repair leg is RUN, over durable truth, and it must converge. A best-effort delivery class whose repair
        // is a claim in a comment is exactly what the plan refuses to accept.
        observer.repair(store);
        settle(observer, store);

        equal(observer.projection(store), whole, fixture,
                "the repair sweep rebuilt the surface from the durable store, including the publish the crash "
                        + "dropped - the second route of the two-route derived-metadata contract, executed");
    }

    // --- the delivery classes, told apart ---------------------------------------------------------------------------

    private static void anEnqueuedNoteIsDurableWhenTheCallbackReturns(PublicationHookFixture fixture,
                                                                      FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/enqueued");

        commit(publication(store, List.of(observer.create())), artifact);

        isTrue(!observer.enqueued(store).isEmpty(), fixture,
                "a " + Delivery.DURABLE_AFTER_ENQUEUE + " surface holds a DURABLE note the moment the callback "
                        + "returned - that is the whole difference from best-effort, and it is read back out of the "
                        + "store rather than out of the instance");
        equal(observer.projection(store), Map.of(), fixture,
                "and it has not yet delivered anything: the effect belongs to the drain, not to the publish thread");

        observer.drain(store);

        equal(observer.enqueued(store), Map.of(), fixture, "the drain consumes the note");
        equal(observer.projection(store), observer.converged(List.of(artifact)), fixture,
                "and delivers exactly the surface the note described");
    }

    private static void aRepeatedDrainLeavesTheSameSurface(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Observer observer = (Observer) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/drained-twice");

        commit(publication(store, List.of(observer.create())), artifact);
        observer.drain(store);
        Map<String, String> once = observer.projection(store);
        observer.drain(store);

        equal(observer.projection(store), once, fixture,
                "a drain is replayable: a note redelivered after a crashed drain converges rather than doubling");
        equal(once, observer.converged(List.of(artifact)), fixture, "and the drained surface is the converged one");
    }

    // --- what never reaches an observer at all ----------------------------------------------------------------------

    private static void aQuarantinedOrRejectedPublishIsNeverObserved(PublicationHookFixture fixture,
                                                                     FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        for (PublishInterceptor.Disposition disposition : List.of(
                PublishInterceptor.Disposition.QUARANTINE, PublishInterceptor.Disposition.REJECT)) {
            ArtifactDescriptor artifact = descriptor("/kit/unobserved-" + disposition.name().toLowerCase(Locale.ROOT));
            Publication.Commit committed = commit(
                    publication(store, List.of(verdict(disposition), observer.create())), artifact);

            equal(committed.disposition(), disposition, fixture, "the chain routed the publication");
            isTrue(!committed.visible(), fixture, disposition + " commits no visibility");
            settle(observer, store);
            // Keyed rather than compared whole: a QUARANTINE writes a review pointer, which legitimately fires the
            // withhold-change feed, and an observer subscribed to that feed may hold a row for it. What must never
            // appear is the PUBLISH row - the observer rides an accepted publish and has no say in a disposition
            // already reached.
            Map<String, String> surface = observer.projection(store);
            for (String key : observer.converged(List.of(artifact)).keySet()) {
                isTrue(!surface.containsKey(key), fixture,
                        "a " + disposition + " screen never reaches an after-commit observer, yet the surface holds "
                                + "the publish row " + key + " for it");
            }
        }
    }

    private static void theWithholdFeedFiresOnlyOnATransition(PublicationHookFixture fixture,
                                                              FaultInjectingStore store) throws Exception {
        Observer observer = (Observer) fixture;
        List<String> transitions = new ArrayList<>();
        PublicationObserver feed = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) {
                transitions.add("on:" + subject.path());
            }

            @Override
            public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) {
                transitions.add("off:" + subject.path());
            }
        };
        Publication publication = publication(store, List.of(feed, observer.create()));
        String blob = commit(publication, descriptor("/kit/feed")).hash();

        publication.link("/quarantine/kit/feed", blob);
        equal(transitions, List.of("on:/kit/feed"), fixture,
                "a freshly linked review pointer fires the transition-ON leg once, with the /quarantine prefix "
                        + "stripped off the served path");

        publication.link("/quarantine/kit/feed", blob);
        equal(transitions, List.of("on:/kit/feed"), fixture,
                "and an idempotent converge re-link is an overwrite, not a transition, so it raises nothing - a feed "
                        + "consumer must not see a hold arrive twice because a sweep re-ran");

        publication.unpublish("/quarantine/kit/feed");
        equal(transitions, List.of("on:/kit/feed", "off:/kit/feed"), fixture,
                "removing the review pointer fires the transition-OFF leg once");

        publication.unpublish("/quarantine/kit/feed");
        equal(transitions, List.of("on:/kit/feed", "off:/kit/feed"), fixture,
                "and removing an absent pointer removes nothing and notifies nothing");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** Bring a fixture's surface to the state its delivery class calls "delivered": a best-effort surface already is,
     *  a durable-after-enqueue one needs its drain. Every convergence comparison goes through here, so the two classes
     *  are compared against their own definition of delivered rather than against each other's. */
    private static void settle(Observer observer, ArtifactStore store) throws IOException {
        if (observer.delivery() == Delivery.DURABLE_AFTER_ENQUEUE) {
            observer.drain(store);
        }
    }

    /** An observer that always fails, so containment is exercised whatever the fixture's own hook does. */
    private static PublicationObserver throwing(IOException failure) {
        return (_, _) -> {
            throw failure;
        };
    }

    /** A screen answering a fixed verdict, to drive the non-accepted dispositions past the observers. */
    private static PublishInterceptor verdict(PublishInterceptor.Disposition disposition) {
        return new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                return disposition;
            }
        };
    }
}
