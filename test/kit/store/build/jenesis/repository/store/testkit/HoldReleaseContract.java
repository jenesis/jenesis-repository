package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Release;
import build.jenesis.repository.store.testkit.PublicationHookFixture.ReleaseHook;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Role;

import static build.jenesis.repository.store.testkit.PublicationHookContract.BODY;
import static build.jenesis.repository.store.testkit.PublicationHookContract.commit;
import static build.jenesis.repository.store.testkit.PublicationHookContract.descriptor;
import static build.jenesis.repository.store.testkit.PublicationHookContract.equal;
import static build.jenesis.repository.store.testkit.PublicationHookContract.isTrue;
import static build.jenesis.repository.store.testkit.PublicationHookContract.publication;
import static build.jenesis.repository.store.testkit.PublicationHookContract.thrownBy;

/**
 * The third role: the pre-commit hold-release hook. Despite the name downstream gave it
 * ({@code gate.HoldReleaseObserver}) it is <b>not</b> a {@link PublicationObserver} and must never be routed through
 * the contained after-commit path - it runs while a reviewer's release is still in flight, it propagates, and the
 * release becomes visible only once every hook has succeeded.
 *
 * <p>Downstream owns the SPI and every implementation, so the kit reaches a hook through the
 * {@link ReleaseHook} adapter and drives the deployment's own release surface through {@link Release}. That is not a
 * workaround for the repository split: a release hook genuinely is not discovered by {@link Publication}, so a kit
 * that reached it through a core type would be inventing a coupling the product does not have.
 *
 * <p>The poison hook is the kit's, never the provider's: a fixture is never asked to sabotage its own implementation,
 * because what is under test is that <em>the surface</em> leaves the hold safely retryable when any hook on the
 * fan-out fails, and that the hooks which already ran converge rather than double-promoting on the retry.
 */
final class HoldReleaseContract {

    private HoldReleaseContract() {
    }

    static void checks(List<PublicationHookContract.Check> checks) {
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_RELEASE_HOOK_IS_NOT_A_CONTAINED_PUBLICATION_OBSERVER,
                "a release hook is not a PublicationObserver, so no registration can contain its failure",
                HoldReleaseContract::aReleaseHookIsNotAnObserver));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_THROWING_HOOK_PROPAGATES_AND_LEAVES_THE_HOLD_SAFE,
                "a throwing hook propagates and leaves the hold in its safe pre-mutation state",
                HoldReleaseContract::aThrowingHookLeavesTheHoldSafe));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.HOOKS_THAT_RAN_BEFORE_THE_FAILURE_ARE_IDEMPOTENT_ON_RETRY,
                "the hooks that ran before the failure are idempotent, so the retry converges",
                HoldReleaseContract::hooksThatRanAreIdempotentOnRetry));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.THE_RELEASE_IS_VISIBLE_ONLY_AFTER_EVERY_HOOK_SUCCEEDED,
                "the release becomes visible only after every hook has succeeded",
                HoldReleaseContract::visibleOnlyAfterEveryHookSucceeded));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_STORE_FAULT_MID_FAN_OUT_LEAVES_THE_HOLD_SAFE,
                "a store outage inside the fan-out leaves the hold standing and retryable",
                HoldReleaseContract::aStoreFaultLeavesTheHoldSafe));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_DISCARD_DROPS_THE_RECORD_WITHOUT_PROMOTING_AN_OVERRIDE,
                "a discard drops the hook's per-version record and promotes no override",
                HoldReleaseContract::aDiscardPromotesNoOverride));
        checks.add(new PublicationHookContract.Check(
                PublicationHookContract.Property.A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD,
                "a hook is a no-op for a path its own hold kind never held",
                HoldReleaseContract::aHookIsANoOpForAPathItNeverHeld));
    }

    // --- the structural claim first ---------------------------------------------------------------------------------

    private static void aReleaseHookIsNotAnObserver(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        Object hook = release.create();

        isTrue(!(hook instanceof PublicationObserver), fixture,
                "a hold-release hook must not be a PublicationObserver: one `uses PublicationObserver` clause "
                        + "discovers the after-commit family, and a hook that answered it would have its failure "
                        + "logged and swallowed while a reviewer's release went visible anyway");
        isTrue(!(hook instanceof PublishInterceptor), fixture,
                "and it is not a screen either - it runs on the release surface, not on the publish path");
        equal(Role.of(hook), Role.PRE_COMMIT_RELEASE_HOOK, fixture, "so the derivation places it in the third role");

        // The outcome this role must never have, demonstrated rather than asserted about: routed through the observer
        // list, exactly the same failure disappears and the mutation stands.
        ArtifactDescriptor artifact = descriptor("/kit/wrongly-contained");
        PublicationObserver miswired = (_, _) -> {
            throw new IOException("the override could not be promoted");
        };
        Publication.Commit stands = commit(publication(store, List.of(miswired)), artifact);
        isTrue(stands.visible(), fixture,
                "this is what the contained path does with an identical failure - the mutation stands and nothing is "
                        + "retried. Routing a hold-release hook here would turn 'the override could not be recorded' "
                        + "into 'the artifact is released', which is the fail-open direction the role exists to "
                        + "prevent.");
    }

    // --- the fail-closed legs ----------------------------------------------------------------------------------------

    private static void aThrowingHookLeavesTheHoldSafe(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/held-then-failed";
        release.hold(store, path, BODY.getBytes(StandardCharsets.UTF_8));
        isTrue(release.held(store, path), fixture, "the path is held before the release is attempted");
        isTrue(!release.visible(store, path), fixture, "and it does not serve while held");

        Throwable failed = thrownBy(() -> release.release(store, path,
                List.of(release.create(), poison(new IOException("the override store is down")))));

        isTrue(failed instanceof IOException, fixture,
                "a throwing hook PROPAGATES out of the release surface - it is pre-commit and fail-closed, never "
                        + "contained (was " + failed + ")");
        // Re-derived from durable state, never from what the surface remembered.
        isTrue(release.held(store, path), fixture,
                "and the quarantine/release pointer is left in its SAFE pre-mutation state: still held, so a reviewer "
                        + "can retry. Leaving a hold in place is always safe; releasing one whose side-effects did "
                        + "not land is the disclosure.");
        isTrue(!release.visible(store, path), fixture, "the artifact still does not serve");
    }

    private static void hooksThatRanAreIdempotentOnRetry(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/retried";
        release.hold(store, path, BODY.getBytes(StandardCharsets.UTF_8));

        // The fixture's own hook runs FIRST and succeeds; the poison behind it fails the release. Whatever the hook
        // recorded is now durable, and the retry re-runs it.
        isTrue(thrownBy(() -> release.release(store, path,
                List.of(release.create(), poison(new IOException("the second hold kind is down")))))
                != null, fixture, "the poisoned fan-out fails the release");
        isTrue(release.held(store, path), fixture, "leaving the hold standing");

        release.release(store, path, List.of(release.create()));

        isTrue(release.visible(store, path), fixture, "the retry completes the release");
        isTrue(!release.held(store, path), fixture, "and lifts the hold");
        Optional<String> promoted = release.override(store, path);
        isTrue(promoted.isPresent(), fixture,
                "the hook promoted its retroactive hold record into an override marker, so a later enforcement sweep "
                        + "never re-holds what a human has cleared");
        isTrue(!release.records(store, path), fixture, "and its per-version hold record is gone");

        // A third run - the release surface is retried once more, as a stuck reviewer or a resumed job would - and
        // nothing doubles.
        release.release(store, path, List.of(release.create()));
        equal(release.override(store, path), promoted, fixture,
                "a hook that already ran is idempotent on a further retry: the override is upserted, never promoted "
                        + "twice, and the retry converges rather than compounding");
        isTrue(!release.records(store, path), fixture, "and the record stays gone");
    }

    private static void visibleOnlyAfterEveryHookSucceeded(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/partly-released";
        release.hold(store, path, BODY.getBytes(StandardCharsets.UTF_8));

        // The fixture's hook succeeds and the one behind it fails: a surface that made the release visible as it went
        // would have published a half-released artifact here.
        isTrue(thrownBy(() -> release.release(store, path,
                List.of(release.create(), poison(new IOException("the reachability hold is down")))))
                != null, fixture, "the release fails on the hook behind the successful one");

        isTrue(!release.visible(store, path), fixture,
                "the release is NOT visible: it becomes visible only after EVERY hook succeeded, so a partial "
                        + "fan-out never serves an artifact whose remaining hold kinds were never cleared");
        isTrue(release.held(store, path), fixture, "and the hold is still what a reviewer sees");

        release.release(store, path, List.of(release.create()));
        isTrue(release.visible(store, path), fixture, "and once every hook succeeds, it serves");
    }

    private static void aStoreFaultLeavesTheHoldSafe(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/store-fault";
        release.hold(store, path, BODY.getBytes(StandardCharsets.UTF_8));

        // The same claim, driven by a backend outage rather than a poisoned hook - a hook whose own write fails must
        // not swallow it into "released".
        for (String space : release.namespaces()) {
            Predicate<String> keys = FaultInjectingStore.keyPrefix(space);
            store.failEveryOn(FaultInjectingStore.Op.WRITE, keys);
            store.failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, keys);
            store.failEveryOn(FaultInjectingStore.Op.DELETE, keys);
            store.failEveryOn(FaultInjectingStore.Op.READ_VERSIONED, keys);
        }
        Throwable failed = thrownBy(() -> release.release(store, path, List.of(release.create())));
        store.heal();

        isTrue(failed != null, fixture,
                "a hook whose own store is down must fail the release rather than reporting success: 'I could not "
                        + "record the override' and 'the override is recorded' are opposite answers");
        isTrue(release.held(store, path), fixture,
                "and the hold is re-read from durable state and still standing - the crash point is verified from the "
                        + "store, not from the surface's memory");
        isTrue(!release.visible(store, path), fixture, "nothing was released");

        release.release(store, path, List.of(release.create()));
        isTrue(release.visible(store, path), fixture, "and the retry, once the backend is back, converges");
        isTrue(release.override(store, path).isPresent(), fixture, "with the override finally promoted");
    }

    private static void aDiscardPromotesNoOverride(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/discarded";
        release.hold(store, path, BODY.getBytes(StandardCharsets.UTF_8));
        isTrue(release.records(store, path), fixture, "the hook holds a per-version record for the held path");

        release.discard(store, path, List.of(release.create()));

        isTrue(!release.records(store, path), fixture,
                "a discard drops the record, so a thrown-away version's holds/ row does not dangle forever - a "
                        + "discarded version has no published sidecar, so no eviction or reconcile sweep would ever "
                        + "reach it");
        equal(release.override(store, path), Optional.empty(), fixture,
                "and promotes NO override: no human cleared the finding, so nothing may stop a future sweep from "
                        + "holding these bytes again");
        isTrue(!release.visible(store, path), fixture, "a discarded artifact does not serve");
        isTrue(!release.held(store, path), fixture, "and is no longer in the review queue either");
    }

    private static void aHookIsANoOpForAPathItNeverHeld(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Release release = (Release) fixture;
        String path = "/kit/never-held";
        commit(publication(store, List.of()), descriptor(path));

        Throwable failed = thrownBy(() -> release.release(store, path, List.of(release.create())));

        isTrue(failed == null, fixture,
                "a release of a path this hook's hold kind never held is a no-op, not a failure - which is what makes "
                        + "registering an observer for a hold kind a deployment does not use harmless (was "
                        + failed + ")");
        equal(release.override(store, path), Optional.empty(), fixture,
                "and nothing is promoted: an override marker for a hold that never existed would suppress a future, "
                        + "legitimate hold on these bytes");
        isTrue(!release.records(store, path), fixture, "no record is invented either");
    }

    /** A hook that always fails - the kit's, never the provider's, because what is under test is what the release
     *  surface does when any hook on the fan-out throws. */
    private static ReleaseHook poison(IOException failure) {
        return new ReleaseHook() {
            @Override
            public void onReleased(ArtifactStore store, String path) throws IOException {
                throw failure;
            }

            @Override
            public void onDiscarded(ArtifactStore store, String path) throws IOException {
                throw failure;
            }
        };
    }
}
