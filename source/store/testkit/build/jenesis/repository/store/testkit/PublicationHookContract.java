package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Delivery;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Role;

/**
 * The executable contract of the publication-hook family: the {@link PublicationObserver} javadoc's eleven clauses and
 * the {@link PublishInterceptor} javadoc's thirteen, plus the pre-commit hold-release hook's, stated as checks a
 * {@link PublicationHookFixture} runs.
 *
 * <p><b>The role split is the kit.</b> The whole family is discovered through one {@code uses PublicationObserver}
 * clause and {@link Publication} splits the list by {@code instanceof PublishInterceptor}. The two halves have
 * <em>opposite</em> failure semantics - an observer's throw is swallowed and the publish stands, a screen's throw
 * fails the write closed - so the single most damaging thing a kit could do is run one under the other's legs and
 * report a green. {@link #checks(PublicationHookFixture)} therefore re-derives the role from the fixture's own
 * instance ({@link Role#of}, the same test {@code Publication} performs), refuses a fixture whose declaration
 * disagrees, and hands out only the checks that role's contract actually binds. There is no way to opt a screen into
 * the contained legs.
 *
 * <p><b>The core ships no interceptor and no observer</b>, which the {@code PublishInterceptor} block says out
 * loud. Every fixture here is therefore synthetic, and that is the point rather than a compromise: the checks assert
 * the SPI's stated contract instead of one shipped implementation's habits, and the census fails the day a source
 * module provides a hook without one.
 *
 * <p><b>Crash claims follow the commit protocol.</b> Each {@link CrashPoint} is injected with the shared
 * {@link FaultInjectingStore} - armed on the <em>screen</em> path, which nothing had ever done, so the window between
 * {@link PublishInterceptor#committed} and the commit point was until now untested - and every crash check re-derives
 * from durable state that the crash landed where it says. A point that stopped biting fails rather than passing
 * vacuously.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the hook, the property and the
 * expectation, so this stays {@code java.base} + the store SPI and the downstream distribution can require it for its
 * own fixtures exactly as it already requires the rest of this module. The JUnit driver lives under {@code test/**}
 * and turns each check into one dynamic test.
 */
public final class PublicationHookContract {

    /** The body every check publishes. Small, and the same for every check so a hash is stable across a replay. */
    static final String BODY = "the-artifact-body";

    /**
     * One documented clause, keyed to the role whose contract states it. The enum is the kit's vocabulary: a fixture
     * excludes a property by name and reason, and the census fails on a property no fixture anywhere exercises, so the
     * contract can never shrink by attrition.
     */
    public enum Property {

        // --- every role -------------------------------------------------------------------------------------------

        /** The role a hook is held to comes from {@code instanceof}, not from what the fixture says - asserted for
         *  every fixture, because a mis-keyed hook is tested under the opposite failure semantics and passes. */
        THE_ROLE_IS_DERIVED_FROM_THE_INSTANCE(null),
        /** A hook writes only under the key prefixes it declared; judged by walking the store, not by intent. */
        THE_HOOK_STAYS_INSIDE_ITS_DECLARED_NAMESPACES(null),

        // --- after-commit observer (PublicationObserver clauses) --------------------------------------------------

        /** Clause 7. A throwing observer is logged and swallowed: the publish stays linked and served, the removal
         *  stays removed, and the later observers on the list still run. */
        A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 7, the detail only the code states: the containment is of {@code Exception}, so an {@link Error}
         *  escapes. A kit that armed its probes with {@code RuntimeException} alone would never notice. */
        AN_ERROR_ESCAPES_THE_OBSERVER_CONTAINMENT(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 2. A byte-identical re-publish notifies again, so the surface must upsert - never blind-append or
         *  blind-increment. */
        A_DUPLICATE_DELIVERY_CONVERGES(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 6. The derived state lands under the tenant/repository scope the artifact was published into. */
        THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 7's blast radius, in the only direction that matters: a lost call may leave the surface stale, but
         *  the store still serves the artifact and still holds what was held. The observer cannot hide either. */
        A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 11 and the plan's gate 5. The mutation-to-callback window is lossy for <em>every</em> class the seam
         *  supports; a fixture claiming commit-coupled at-least-once is refused by name. */
        THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL(Role.AFTER_COMMIT_OBSERVER),
        /** Clause 11's heal-all, executed rather than claimed: the fixture's own walk/sweep repair runs, with a fresh
         *  instance over durable truth, and the surface converges. */
        A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR(Role.AFTER_COMMIT_OBSERVER),
        /** What {@link Delivery#DURABLE_AFTER_ENQUEUE} means and best-effort does not: the note is durable the moment
         *  the callback returns, before any drain has run. */
        AN_ENQUEUED_NOTE_IS_DURABLE_WHEN_THE_CALLBACK_RETURNS(Role.AFTER_COMMIT_OBSERVER, Delivery.DURABLE_AFTER_ENQUEUE),
        /** ... and the drain that follows it is replayable: draining twice leaves the same surface. */
        A_REPEATED_DRAIN_LEAVES_THE_SAME_SURFACE(Role.AFTER_COMMIT_OBSERVER, Delivery.DURABLE_AFTER_ENQUEUE),
        /** The seam's own precondition: {@code onPublished} fires only for an accepted, laid-out, visible publish -
         *  never for a quarantined or rejected screen. */
        A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED(Role.AFTER_COMMIT_OBSERVER),
        /** The withhold-change feed fires at the durable transitions and only on an actual transition, so a converge
         *  re-mark raises nothing. */
        THE_WITHHOLD_FEED_FIRES_ONLY_ON_A_DURABLE_TRANSITION(Role.AFTER_COMMIT_OBSERVER),

        // --- publish interceptor (PublishInterceptor clauses) -----------------------------------------------------

        /** Clause 3. {@code ACCEPT} is the neutral answer, {@code withheld} answers {@code false} for "serves", and
         *  the shipped chain is empty - with no provider on the graph every upload is accepted and served. */
        ACCEPT_IS_THE_NEUTRAL_ANSWER_AND_AN_EMPTY_CHAIN_ACCEPTS(Role.PUBLISH_INTERCEPTOR),
        /** Clause 4. The policy is additive: every screen in the chain participates, there is nothing to select and
         *  so nothing to fail at resolution. */
        EVERY_SCREEN_IN_THE_CHAIN_PARTICIPATES(Role.PUBLISH_INTERCEPTOR),
        /** Clause 5. The screen is handed a re-stream of the stored blob, never the upload's bytes; the whole-document
         *  sibling read throws past its ceiling while the bounded one honours the caller's own limit and reports the
         *  overflow instead of failing on it. */
        THE_CONTENT_VIEW_RESTREAMS_THE_BLOB_UNDER_TWO_DIFFERENT_BOUNDS(Role.PUBLISH_INTERCEPTOR),
        /** Clause 6. {@code Content.store()} and the stores handed to {@code committed} and {@code withheld} are the
         *  one doubly-scoped view the publication routed through. */
        THE_VERDICT_LEGS_RECEIVE_THE_PUBLICATIONS_OWN_SCOPED_STORE(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7, the headline. A throwing {@code assess} fails the publish and links no pointer of any kind. */
        A_THROWING_ASSESS_FAILS_THE_PUBLISH_WITH_NO_POINTER_LINKED(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7. A throwing {@code committed} fails the publish too - it is a verdict leg, not an observer leg. */
        A_THROWING_COMMITTED_FAILS_THE_PUBLISH(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7. A throwing {@code withheld} fails the read <em>closed</em> rather than serving a path the chain
         *  could not clear - on the serve path and on the enumeration seam alike. */
        A_THROWING_WITHHELD_FAILS_THE_READ_CLOSED(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7's detail on this side: containment is of {@code Exception}, so an {@link Error} out of an
         *  inherited observer leg escapes even though an {@code IOException} would not. */
        AN_ERROR_ESCAPES_BOTH_SIDES_OF_THE_CONTAINMENT(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7's one genuinely per-implementation obligation: a screen must not catch its own store failure into
         *  a default {@code ACCEPT}. Driven by faulting exactly the keys the fixture says its screen reads. */
        A_SCREEN_DOES_NOT_CATCH_ITS_OWN_STORE_FAILURE_INTO_AN_ACCEPT(Role.PUBLISH_INTERCEPTOR),
        /** Clause 7's other half: the observer legs an interceptor inherits stay contained. One class, two failure
         *  modes, keyed by method. */
        THE_INHERITED_OBSERVER_LEGS_STAY_CONTAINED(Role.PUBLISH_INTERCEPTOR),
        /** Clause 8. {@code withheld} runs on every serve and every enumeration, renders stored state only, and
         *  writes nothing. */
        WITHHELD_IS_A_PURE_READ_ON_EVERY_SERVE_AND_ENUMERATION(Role.PUBLISH_INTERCEPTOR),
        /** Clause 9. Because the read side is re-consulted rather than latched, a verdict that changes after the fact
         *  retracts an already-linked artifact with no sweep and no pointer rewrite. */
        A_LATER_VERDICT_RETRACTS_WITHOUT_A_POINTER_REWRITE(Role.PUBLISH_INTERCEPTOR),
        /** Clause 10. The discovered chain is loaded once at class load and cached for the process; an injected chain
         *  is the embedder's and is sorted on every construction. */
        THE_DISCOVERED_CHAIN_IS_CACHED_AND_AN_INJECTED_ONE_IS_SORTED_PER_CONSTRUCTION(Role.PUBLISH_INTERCEPTOR),
        /** Clause 11. The chain runs in ascending {@code order()} and the strongest disposition routes the
         *  publication, whatever order it was voted in. */
        THE_CHAIN_RUNS_IN_ASCENDING_ORDER_AND_THE_STRONGEST_DISPOSITION_ROUTES(Role.PUBLISH_INTERCEPTOR),
        /** Clause 11, the first fact a naive kit gets backwards: {@code assess} is <b>not</b> short-circuited by a
         *  {@code REJECT}. Every screen is still asked, so a screen that records what it saw sees every artifact. */
        ASSESS_IS_NOT_SHORT_CIRCUITED_BY_A_REJECT(Role.PUBLISH_INTERCEPTOR),
        /** Clause 11, the second: {@code withheld} <b>is</b> short-circuited, the first {@code true} winning, so a
         *  screen must never rely on being asked. */
        WITHHELD_IS_SHORT_CIRCUITED_ON_THE_FIRST_TRUE(Role.PUBLISH_INTERCEPTOR),
        /** Clauses 11 and 13. {@code committed} fires over the whole chain, in the same order, for every disposition -
         *  including for the screens that voted {@code ACCEPT}. */
        COMMITTED_FIRES_FOR_EVERY_DISPOSITION_OVER_THE_WHOLE_CHAIN(Role.PUBLISH_INTERCEPTOR),
        /** Clause 2. A byte-identical re-commit runs the whole chain again, reaches the same disposition, and calls
         *  {@code committed} again - which must therefore upsert rather than append or increment. */
        A_BYTE_IDENTICAL_REPLAY_REACHES_THE_SAME_VERDICT_AND_UPSERTS(Role.PUBLISH_INTERCEPTOR),
        /** Clause 1. One instance serves every publishing thread <em>and</em> every reading thread; a screen keeping
         *  per-call state in a field answers the wrong artifact under concurrency. */
        ONE_INSTANCE_SERVES_CONCURRENT_PUBLISHES_AND_READS(Role.PUBLISH_INTERCEPTOR),
        /** Clause 12. There is no timeout and no way to abandon a chain part-way: a slow screen is awaited in full,
         *  and a screen that cannot finish throws, which fails the publish closed. */
        THE_CHAIN_IS_AWAITED_IN_FULL_AND_NEVER_ABANDONED_PART_WAY(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13. Store-then-gate: the body is content-addressed first, the screen sees a stored blob, and no
         *  pointer of the publication's own is linked before the chain has voted. */
        STORE_THEN_GATE_LINKS_NO_POINTER_BEFORE_THE_CHAIN_VOTED(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13. A {@code QUARANTINE}'s review pointer is written inside the chain run, before {@code committed}
         *  fires - so a screen notified of a quarantine can already read it. */
        A_QUARANTINE_REVIEW_POINTER_IS_WRITTEN_BEFORE_COMMITTED_FIRES(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13, the trap: {@code committed} fires <em>before</em> the accepted layout and before the commit
         *  point, so an {@code ACCEPT} reported there does not mean the artifact is visible. */
        COMMITTED_FIRES_BEFORE_THE_COMMIT_POINT_SO_ACCEPT_IS_NOT_VISIBILITY(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13's crash window, never before driven: the process dies between {@code committed} and the
         *  visibility write. The screen believes it accepted an artifact that never served; the replay repairs it, and
         *  only because {@code committed} upserts. */
        THE_COMMITTED_TO_VISIBILITY_CRASH_WINDOW_REPLAYS_CLEAN(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13's earlier window: the blob landed and the process died before the chain ran. Nothing was
         *  screened, nothing serves, and the replay converges onto the same state. */
        THE_BLOB_TO_CHAIN_CRASH_WINDOW_LEAVES_ONLY_AN_UNREFERENCED_BLOB(Role.PUBLISH_INTERCEPTOR),
        /** Clause 13's quarantine window: the review pointer landed and the process died before {@code committed}.
         *  The hold stands, the replay re-notifies, and the withhold feed does not fire twice. */
        THE_QUARANTINE_POINTER_TO_COMMITTED_CRASH_WINDOW_REPLAYS_CLEAN(Role.PUBLISH_INTERCEPTOR),

        // --- pre-commit hold-release hook -------------------------------------------------------------------------

        /** The structural claim first: a hold-release hook is not a {@link PublicationObserver}, so no registration
         *  accident can route it through the contained after-commit outbox. */
        A_RELEASE_HOOK_IS_NOT_A_CONTAINED_PUBLICATION_OBSERVER(Role.PRE_COMMIT_RELEASE_HOOK),
        /** A throwing hook propagates and leaves the quarantine/release pointer in its safe pre-mutation state. */
        A_THROWING_HOOK_PROPAGATES_AND_LEAVES_THE_HOLD_SAFE(Role.PRE_COMMIT_RELEASE_HOOK),
        /** ... and the hooks that ran before the failure are idempotent, so the retry converges rather than
         *  double-promoting an override or stranding a record. */
        HOOKS_THAT_RAN_BEFORE_THE_FAILURE_ARE_IDEMPOTENT_ON_RETRY(Role.PRE_COMMIT_RELEASE_HOOK),
        /** The release becomes visible only after every hook succeeded - never a partly-released artifact. */
        THE_RELEASE_IS_VISIBLE_ONLY_AFTER_EVERY_HOOK_SUCCEEDED(Role.PRE_COMMIT_RELEASE_HOOK),
        /** The same, driven by a store fault rather than a poisoned hook: a backend outage mid-fan-out leaves the hold
         *  in place and retryable, re-verified from durable state. */
        A_STORE_FAULT_MID_FAN_OUT_LEAVES_THE_HOLD_SAFE(Role.PRE_COMMIT_RELEASE_HOOK),
        /** A discard drops the hook's per-version record without promoting an override - no human cleared it. */
        A_DISCARD_DROPS_THE_RECORD_WITHOUT_PROMOTING_AN_OVERRIDE(Role.PRE_COMMIT_RELEASE_HOOK),
        /** A hook is a no-op for a path its own hold kind never held, so registering it in a deployment that does not
         *  use that kind is harmless. */
        A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD(Role.PRE_COMMIT_RELEASE_HOOK);

        private final Role role;
        private final Delivery delivery;

        Property(Role role) {
            this(role, null);
        }

        Property(Role role, Delivery delivery) {
            this.role = role;
            this.delivery = delivery;
        }

        /** The role whose contract states this clause, or {@code null} for a clause every role carries. */
        public Role role() {
            return role;
        }

        /** The delivery class this clause is specific to, or {@code null} when it binds to every class. */
        public Delivery delivery() {
            return delivery;
        }

        /** Whether a fixture in {@code derived} role is held to this clause - and, for a delivery-specific clause,
         *  whether its declared class matches. The role is passed in rather than re-derived per property, because
         *  deriving it means building an instance and a fixture reaches its hook through discovery. */
        public boolean bindsTo(Role derived, PublicationHookFixture fixture) {
            if (role != null && role != derived) {
                return false;
            }
            return delivery == null
                    || (fixture instanceof PublicationHookFixture.Observer observer && observer.delivery() == delivery);
        }
    }

    /**
     * Where a publish is killed, and what durable state each point leaves. Every point is armed on the
     * <em>screen</em> path through {@link FaultInjectingStore} - which is what had never been done, and why the
     * {@code committed}-fires-before-visibility window had never been tested - and every crash check re-derives from
     * durable state that the crash really landed there. A point that stopped biting fails.
     */
    public enum CrashPoint {

        /** The blob landed and the process died before the chain ran: an unreferenced blob, no verdict, no pointer. */
        AFTER_THE_BLOB_BEFORE_THE_CHAIN(Property.THE_BLOB_TO_CHAIN_CRASH_WINDOW_LEAVES_ONLY_AN_UNREFERENCED_BLOB),
        /** A {@code QUARANTINE}'s review pointer landed and the process died before {@code committed} fired. */
        AFTER_THE_QUARANTINE_POINTER_BEFORE_COMMITTED(
                Property.THE_QUARANTINE_POINTER_TO_COMMITTED_CRASH_WINDOW_REPLAYS_CLEAN),
        /** {@code committed} fired and the process died before the declared visibility write - the screen believes it
         *  accepted an artifact that never became visible. */
        AFTER_COMMITTED_BEFORE_THE_VISIBILITY_WRITE(Property.THE_COMMITTED_TO_VISIBILITY_CRASH_WINDOW_REPLAYS_CLEAN),
        /** The visibility write landed and the caller never learned it did: the artifact serves and no after-commit
         *  observer ever saw it - the window {@code Publication} documents and does not close. */
        AFTER_THE_VISIBILITY_WRITE_BEFORE_PUBLISHED(Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL);

        private final Property property;

        CrashPoint(Property property) {
            this.property = property;
        }

        /** The contract property this crash point proves. */
        public Property property() {
            return property;
        }
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a fixture and a fresh, empty, fault-armable store. */
    @FunctionalInterface
    public interface Body {
        void run(PublicationHookFixture fixture, FaultInjectingStore store) throws Exception;
    }

    private PublicationHookContract() {
    }

    /** Every contract check of every role, in declaration order, independent of any fixture. */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(Property.THE_ROLE_IS_DERIVED_FROM_THE_INSTANCE,
                "the role a hook is held to is derived from its instance, not from its fixture",
                PublicationHookContract::theRoleIsDerivedFromTheInstance));
        checks.add(new Check(Property.THE_HOOK_STAYS_INSIDE_ITS_DECLARED_NAMESPACES,
                "the hook writes only under the key prefixes it declared",
                PublicationHookContract::theHookStaysInsideItsDeclaredNamespaces));
        AfterCommitContract.checks(checks);
        InterceptorContract.checks(checks);
        HoldReleaseContract.checks(checks);
        return List.copyOf(checks);
    }

    /**
     * The checks {@code fixture} runs: every check whose property binds to its <em>derived</em> role and declared
     * delivery class, minus the properties it excludes with a reason.
     *
     * <p>This is where a mis-declared fixture dies. The role is re-derived from the instance and compared with what
     * the fixture answers, and a fixture whose sub-interface does not match its instance is refused - because a
     * {@link PublishInterceptor} handed out as an {@link PublicationHookFixture.Observer} would be run through the
     * contained legs and would pass while failing open.
     */
    public static List<Check> checks(PublicationHookFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        Role derived = Role.of(fixture.create());
        if (fixture.role() != derived) {
            throw new AssertionError("The '" + fixture.hook() + "' fixture declares role " + fixture.role()
                    + " while its instance is a " + derived + ". The role is not a fixture's to choose: Publication "
                    + "splits the one discovered list by `instanceof PublishInterceptor`, and testing a screen under "
                    + "the contained observer legs would report a green for a hook that fails open.");
        }
        Class<?> expected = switch (derived) {
            case AFTER_COMMIT_OBSERVER -> PublicationHookFixture.Observer.class;
            case PUBLISH_INTERCEPTOR -> PublicationHookFixture.Interceptor.class;
            case PRE_COMMIT_RELEASE_HOOK -> PublicationHookFixture.Release.class;
        };
        if (!expected.isInstance(fixture)) {
            throw new AssertionError("The '" + fixture.hook() + "' fixture registers a " + derived
                    + " but does not implement " + expected.getSimpleName() + ", so the kit cannot ask it the "
                    + "questions that role's contract needs answered.");
        }
        if (fixture instanceof PublicationHookFixture.Observer observer && !observer.delivery().supported()) {
            throw new AssertionError("The '" + fixture.hook() + "' fixture declares delivery "
                    + observer.delivery() + ", which this seam does not provide. Publication makes the artifact "
                    + "visible and only THEN calls the observer, so a crash in that window loses the call whatever "
                    + "the callback writes once invoked; an outbox written inside the callback buys "
                    + Delivery.DURABLE_AFTER_ENQUEUE + ", not at-least-once observation. Only T-107's pre-commit "
                    + "intent/state machine, proven at every injected crash point, could raise the class - and T-107 "
                    + "has not landed.");
        }
        fixture.unsupported().forEach((property, reason) -> {
            Objects.requireNonNull(property, "unsupported property");
            if (reason == null || reason.isBlank()) {
                throw new AssertionError("The '" + fixture.hook() + "' fixture excludes " + property
                        + " without a reason; an exclusion must say which part of the hook's shape does not have the "
                        + "property, and where the property is proven instead.");
            }
        });
        return checks().stream()
                .filter(check -> check.property().bindsTo(derived, fixture))
                .filter(check -> !fixture.unsupported().containsKey(check.property()))
                .toList();
    }

    // --- the two checks every role runs ----------------------------------------------------------------------------

    private static void theRoleIsDerivedFromTheInstance(PublicationHookFixture fixture, FaultInjectingStore store) {
        Object hook = fixture.create();
        Role derived = Role.of(hook);
        equal(derived, fixture.role(), fixture, "the derived role and the fixture's answer agree");

        // The derivation is Publication's own, restated here so a change to either is caught by the other: a screen IS
        // an observer, so the sub-interface has to be tested first or every screen collapses into the contained class.
        equal(hook instanceof PublishInterceptor, derived == Role.PUBLISH_INTERCEPTOR, fixture,
                "`instanceof PublishInterceptor` and the derived role agree - this is the exact test Publication "
                        + "performs on its discovered list");
        equal(hook instanceof PublicationObserver, derived != Role.PRE_COMMIT_RELEASE_HOOK, fixture,
                "a hook Publication would discover is exactly one that is NOT a pre-commit release hook; a release "
                        + "hook must never be reachable through the contained after-commit path");

        // ... and a fresh instance per simulated process, or a crash check would silently keep an accumulation.
        isTrue(fixture.create() != fixture.create(), fixture,
                "create() answers a fresh instance each call, as a restarted process would build one");
    }

    private static void theHookStaysInsideItsDeclaredNamespaces(PublicationHookFixture fixture,
                                                                FaultInjectingStore store) throws Exception {
        isTrue(!fixture.namespaces().isEmpty(), fixture,
                "a hook that declares no namespace has nothing this check can bound; declare the prefixes it writes");
        List<String> before = keys(store);
        drive(fixture, store);
        List<String> escaped = keys(store).stream()
                .filter(key -> !before.contains(key))
                .filter(key -> !owned(fixture, key))
                .filter(key -> !isPublicationKey(key))
                .toList();
        if (!escaped.isEmpty()) {
            throw failure(fixture, "the hook wrote " + escaped.size() + " key(s) outside its declared namespaces "
                    + fixture.namespaces() + ": " + escaped + ". A derived surface one namespace over is invisible to "
                    + "this fixture's projection and is another plugin's data.");
        }
    }

    /** Exercise {@code fixture}'s hook once through whatever choreography its role rides - so the namespace check is
     *  about a hook that really ran. */
    private static void drive(PublicationHookFixture fixture, FaultInjectingStore store) throws Exception {
        switch (fixture.role()) {
            case AFTER_COMMIT_OBSERVER -> {
                PublicationHookFixture.Observer observer = (PublicationHookFixture.Observer) fixture;
                commit(publication(store, List.of(observer.create())), descriptor("/kit/namespaced"));
            }
            case PUBLISH_INTERCEPTOR -> {
                PublicationHookFixture.Interceptor screen = (PublicationHookFixture.Interceptor) fixture;
                Publication publication = publication(store, List.of(screen.create()));
                commit(publication, descriptor("/kit/namespaced"));
                publication.located("/kit/namespaced");
            }
            case PRE_COMMIT_RELEASE_HOOK -> {
                PublicationHookFixture.Release release = (PublicationHookFixture.Release) fixture;
                release.hold(store, "/kit/namespaced", BODY.getBytes(StandardCharsets.UTF_8));
                release.release(store, "/kit/namespaced", List.of(release.create()));
            }
        }
    }

    /** Whether a key belongs to a namespace the fixture declared. */
    private static boolean owned(PublicationHookFixture fixture, String key) {
        return fixture.namespaces().stream().anyMatch(space -> key.equals(space) || key.startsWith(space + "/"));
    }

    /** The keys {@link Publication} itself owns - not the hook's, and not evidence of an escape. The withhold marker
     *  root is taken from {@link Withheld} rather than spelled out, so this stays one convention with one owner. */
    private static boolean isPublicationKey(String key) {
        return key.startsWith("blobs/") || key.startsWith(ServableNames.PUBLISHED + "/")
                || key.startsWith(Withheld.ROOT) || key.startsWith("gc/");
    }

    // --- shared drivers, so every check runs the one choreography ---------------------------------------------------

    /** A publication over {@code hooks}, split into interceptors and observers exactly as {@link Publication} splits
     *  its own discovered list - the kit never keeps a second opinion about which hook is which. */
    static Publication publication(ArtifactStore store, List<? extends PublicationObserver> hooks) {
        List<PublicationObserver> observers = List.copyOf(hooks);
        return new Publication(store, observers.stream()
                .filter(hook -> hook instanceof PublishInterceptor)
                .map(hook -> (PublishInterceptor) hook)
                .toList(), observers);
    }

    /** One hosted publish through the single choke point: screen, gate, lay out, link the declared visibility last,
     *  notify. Every check drives {@link Publication#commit} rather than assembling the sequence by hand. */
    static Publication.Commit commit(Publication publication, ArtifactDescriptor artifact) throws IOException {
        return commit(publication, artifact, BODY);
    }

    static Publication.Commit commit(Publication publication, ArtifactDescriptor artifact, String body)
            throws IOException {
        return commit(publication, artifact, bytes(body));
    }

    /** The same, over an arbitrary body stream - what a check publishing a large companion needs. Even a check that
     *  only wants a blob in place goes through {@code commit}: the core has exactly one hosted-publish
     *  choreography, and a kit that assembled screen-then-link by hand would be the second one. */
    static Publication.Commit commit(Publication publication, ArtifactDescriptor artifact, InputStream body)
            throws IOException {
        return publication.commit(artifact, body, Publication.Republish.overwrite(),
                _ -> Publication.Visibility.at(artifact.path()));
    }

    static ByteArrayInputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    static ArtifactDescriptor descriptor(String path) {
        return ArtifactDescriptor.at("kit", path);
    }

    /** The SHA-256 hex {@code body} is stored under, so a check can name a blob before publishing it. */
    static String hash(String body) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is mandatory", impossible);
        }
    }

    /** Every stored key, walked rather than listed, so a hook that planted a deep key cannot slip past the namespace
     *  check meant to catch it. */
    static List<String> keys(ArtifactStore store) {
        List<String> keys = new ArrayList<>();
        for (String top : store.list("")) {
            descend(store, top, keys);
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static void descend(ArtifactStore store, String prefix, List<String> keys) {
        List<String> children = store.list(prefix);
        if (children.isEmpty()) {
            keys.add(prefix);
            return;
        }
        for (String child : children) {
            descend(store, prefix + "/" + child, keys);
        }
    }

    // --- assertion helpers (no assertion library: this module stays java.base + the store SPI) -----------------------

    static void equal(Object actual, Object expected, PublicationHookFixture fixture, String what) {
        if (!Objects.deepEquals(actual, expected)) {
            throw failure(fixture, what + " - expected " + expected + " but was " + actual);
        }
    }

    static void isTrue(boolean actual, PublicationHookFixture fixture, String what) {
        if (!actual) {
            throw failure(fixture, what);
        }
    }

    static AssertionError failure(PublicationHookFixture fixture, String message) {
        return new AssertionError(fixture.hook() + ": " + message);
    }

    /** Run {@code body} and answer what it threw, or {@code null} when it returned. Checks assert on the failure the
     *  product produced rather than wrapping every call in a try/catch of their own. */
    static Throwable thrownBy(Attempt body) {
        try {
            body.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    /** Something a check expects to fail. */
    @FunctionalInterface
    interface Attempt {
        void run() throws Exception;
    }
}
