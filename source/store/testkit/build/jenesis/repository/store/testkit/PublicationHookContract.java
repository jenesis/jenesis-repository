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
import build.jenesis.repository.store.testkit.PublicationHookFixture.Interceptor;
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
 * <p><b>Every falsifiable property also declares what must break it</b> (carrying the earlier mechanism here).
 * A check states what a hook must do; nothing in a check states that it <em>could have said otherwise</em>, and
 * the earlier hold-release legs would have passed against a hook that was a no-op from end to end. So each
 * {@link Property} the hook actually owns names one or more {@link Mutation}s - a {@link Mutant} that removes exactly
 * the behaviour the property is about - and the JUnit driver runs the same check body a second time against each,
 * requiring an {@link AssertionError}. A check that survives its property's mutation does not measure that property
 * <em>for that hook</em>, and fails. The declarations are the contract's, never a fixture's: a fixture cannot opt out
 * of falsification, only out of a whole property, and that opt-out is already a reviewed list on the census.
 *
 * <p><b>And the clauses that are about the choreography rather than the hook declare an arrangement instead</b>
 *. Twenty of the forty-six are claims about {@link Publication}'s commit sequence, asserted with kit-owned
 * probes while the fixture's hook is a bystander, so a {@link Mutant} could never falsify them - which meant the
 * falsification leg proved things about implementations and said nothing about the choreography they plug into.
 * {@link ChoreographyMutant} closes nineteen of them by arranging the hooks this kit hands {@code Publication} so the
 * sequence produces the observable a mutated {@code Publication} would; the twentieth crashes before the chain runs at
 * all and stays on the census's reviewed list. What that proves, and what it does not, is stated on
 * {@code ChoreographyMutant} itself rather than left implicit.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the hook, the property and the
 * expectation, so this stays {@code java.base} + the store SPI and the downstream distribution can require it for its
 * own fixtures exactly as it already requires the rest of this module. The JUnit driver lives under {@code test/**}
 * and turns each check into one dynamic test.
 *
 * <h2>Clauses this kit discharges</h2>
 * restating the
 * clause numbers each {@link Property}'s javadoc already opens with. The interceptor half reaches <b>all thirteen</b>
 * {@code PublishInterceptor} clauses - which is the burn-down predicted, since it recorded all thirteen as
 * residue while nothing yet drove that chain. The after-commit half reaches four of {@code PublicationObserver}'s
 * eleven (2, 6, 7, 11); the remaining seven - thread-safety, the absence sentinel, selection, streaming, read purity,
 * lifecycle and ordering - carry no property here and stay checkup rows.
 *
 * <p>The pre-commit hold-release half drives an <b>downstream-owned</b> SPI through a {@code ReleaseHook} adapter, so
 * its clauses are claimed in that repository's own half rather than here: a marker may only name a surface this
 * repository's inventory knows.
 *
 * @jenesis.covers build.jenesis.repository.store.PublishInterceptor 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13
 * @jenesis.covers build.jenesis.repository.store.PublicationObserver 2, 6, 7, 11
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
        /** Clause 6, in both directions. {@code Content.store()} and the stores handed to {@code committed} and
         *  {@code withheld} are the one doubly-scoped view the publication routed through - <em>and</em> the screen's
         *  own derived rows land inside it, never one scope up. The second half was added by this check drove
         *  a real screen under a real scope and read only the kit's probe, so a screen recording its verdict against
         *  the deployment root passed the whole kit. */
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

    /**
     * One deliberately broken deployment object a property's check <em>must</em> fail against, and why. The
     * predicate is the contract's, not the fixture's: a mutation whose removed behaviour a hook's shape genuinely
     * does not have (a read side on a screen that votes only at publish time) is declared inapplicable here, in front
     * of whoever reviews the kit, rather than waived inside the fixture it would excuse.
     */
    public record Mutation(Mutant mutant, Predicate<PublicationHookFixture> appliesTo, String why) {

        public Mutation {
            Objects.requireNonNull(mutant, "mutant");
            Objects.requireNonNull(appliesTo, "appliesTo");
            if (why == null || why.isBlank()) {
                throw new AssertionError("a mutation must say which claim of the property it removes, or a later "
                        + "reader cannot tell a targeted mutation from one that merely happens to fail");
            }
        }

        /** A mutation every fixture's leg of this property must break. */
        public Mutation(Mutant mutant, String why) {
            this(mutant, _ -> true, why);
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
     * What must break each property, keyed by property - the kit's falsification declaration. Read as a
     * table: <em>this</em> property is about <em>that</em> behaviour of the hook, so removing it must turn the check
     * red. A property whose entry is empty is a property no substitution for a hook can falsify, and the census holds
     * that set to a reviewed, reason-bearing list rather than letting an entry quietly shrink to nothing.
     *
     * <p>The mutations are chosen to be the <em>weakest</em> break the property forbids rather than the most
     * destructive one available: {@link Mutant#NO_WORK_AT_ALL} would fail a great many of these, and a table full of
     * it would be a table of trivial mutations satisfying a count while proving nothing. Where a property's check has
     * two independent halves - "the surface converged" and "the replay upserted onto it" - each half carries its own
     * mutation, so a fixture that only exercises one is still falsified on the one it exercises.
     *
     * <p><b>The interceptor half is mostly empty, and that is the finding rather than an oversight.</b> Eighteen of
     * the twenty-five {@link Role#PUBLISH_INTERCEPTOR} clauses are claims about {@link Publication}'s own commit
     * choreography - the chain's ordering, its short-circuiting, where a review pointer lands relative to
     * {@code committed}, what escapes the containment - and the kit asserts them with its own probe screens while the
     * fixture's screen rides along in the chain. No substitution for a <em>provider's</em> hook can falsify a claim
     * about the core's own final class, so those carry no mutation <em>here</em>. The seven that remain are the
     * clauses a provider actually owns.
     *
     * <p><b>The choreography half is falsified elsewhere, by a different subject</b>. Those clauses are not
     * unfalsifiable, they are un-falsifiable-<em>by-a-hook</em>: {@link ChoreographyMutant} arranges the hooks this
     * kit hands {@link Publication} so the commit sequence produces exactly the observable a mutated
     * {@code Publication} would produce, and the census pairs nineteen of the twenty clauses with the arrangement each
     * must fail under. Read {@code ChoreographyMutant} for the honest limit of that: it is a faithful simulation of
     * the defect rather than the defect itself. Exactly one clause - the crash before the chain runs at all - is
     * reachable by neither table, and it is the whole of the census's reviewed {@code UNFALSIFIABLE} list.
     */
    public static Map<Property, List<Mutation>> mutations() {
        Map<Property, List<Mutation>> mutations = new EnumMap<>(Property.class);

        // --- every role ---------------------------------------------------------------------------------------------
        mutations.put(Property.THE_ROLE_IS_DERIVED_FROM_THE_INSTANCE, List.of(
                new Mutation(Mutant.A_MISDERIVED_ROLE,
                        fixture -> fixture.role() != Role.PUBLISH_INTERCEPTOR,
                        "the derivation half. A hook that also answers a neighbouring role's sub-interface is tested "
                                + "under the opposite failure semantics and passes, which is the one outcome this "
                                + "property exists to make impossible - so the derivation has to be read off the "
                                + "instance and compared, not assumed. It is not declared for a screen because "
                                + "Interceptor.create() is typed PublishInterceptor: there is no wrapper that could "
                                + "hand one out as anything else, which is a property of the fixture SPI"),
                new Mutation(Mutant.A_SHARED_INSTANCE,
                        "the freshness half, which is a separate claim and the one every crash leg leans on: create() "
                                + "must answer a fresh instance per simulated process, or an in-memory accumulation "
                                + "survives a restart and a crash check measures the surviving object instead of the "
                                + "durable store")));
        mutations.put(Property.THE_HOOK_STAYS_INSIDE_ITS_DECLARED_NAMESPACES, List.of(
                new Mutation(Mutant.A_KEY_OUTSIDE_THE_NAMESPACES,
                        "the property is judged by walking the store, so the walk has to be able to see a key that "
                                + "escaped. One planted key per leg, outside every declared space and outside every "
                                + "key Publication itself owns, is exactly the 'one namespace over' this forbids")));

        // --- the after-commit observer -------------------------------------------------------------------------------
        mutations.put(Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        "containment is a claim about an observer that RAN: the fixture's own hook sits behind the "
                                + "throwing probe and must be shown to have recorded the publish, or 'the failure was "
                                + "contained' is indistinguishable from 'nothing happened at all'"),
                new Mutation(Mutant.A_REPAIR_THAT_DOES_NOTHING,
                        "and the second half is gate 4's: a contained failure must leave a trace AND a route back. "
                                + "The route back is the fixture's repair leg, and this check RUNS it - so a repair "
                                + "that does nothing must fail here rather than leaving the claim in a comment")));
        mutations.put(Property.A_DUPLICATE_DELIVERY_CONVERGES, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL, PublicationHookContract::recordsOnPublish,
                        "the preamble is that the first delivery converged; an observer that records nothing must "
                                + "fail it rather than sailing into a comparison of two empty surfaces"),
                new Mutation(Mutant.A_ROW_PER_DELIVERY, PublicationHookContract::recordsOnPublish,
                        "and the repeat must be able to notice an append. One extra row from the second delivery on "
                                + "is the smallest divergence this property exists to catch - a surface that grows "
                                + "once per notification of bytes that never changed")));
        mutations.put(Property.THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE, List.of(
                new Mutation(Mutant.A_ROOT_SCOPED_RECORD, PublicationHookContract::recordsOnPublish,
                        "the whole property is WHERE the row lands, so the mutation moves the row and changes nothing "
                                + "else: a derived row written one scope up is a row recorded for the wrong "
                                + "repository, and it reads as a correct row from anywhere but the scope it belongs "
                                + "in (§6)")));
        mutations.put(Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, List.of(
                new Mutation(Mutant.A_CONVERGENCE_THE_SEEDED_STORE_ALREADY_HAS,
                        "the observer-facing half of the blast radius is that the surface is demonstrably STALE after "
                                + "a lost call. A fixture whose declared converged view the untouched store already "
                                + "satisfies makes that unprovable while the check still passes - the "
                                + "converged-over-an-empty-answer shape hit by hand")));
        mutations.put(Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, List.of(
                new Mutation(Mutant.A_CONVERGENCE_THE_SEEDED_STORE_ALREADY_HAS,
                        "same shape, and it matters more here: this leg is what refuses a fixture the "
                                + "commit-coupled-at-least-once class, so a declared convergence that is true of an "
                                + "empty surface would let the refusal be reached without observing anything")));
        mutations.put(Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, List.of(
                new Mutation(Mutant.A_REPAIR_THAT_DOES_NOTHING,
                        "the property IS that the repair leg is executable and converges, so the mutation is the "
                                + "repair reduced to what a comment would have been")));
        mutations.put(Property.AN_ENQUEUED_NOTE_IS_DURABLE_WHEN_THE_CALLBACK_RETURNS, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        "the durable-after-enqueue class is exactly the claim that a note EXISTS the moment the "
                                + "callback returned; a callback that enqueues nothing must fail here, which is what "
                                + "keeps the class from being a label")));
        mutations.put(Property.A_REPEATED_DRAIN_LEAVES_THE_SAME_SURFACE, List.of(
                new Mutation(Mutant.A_DRAIN_THAT_IS_NOT_REPLAYABLE, PublicationHookContract::recordsOnPublish,
                        "the property is about the SECOND drain, so the mutation is one extra row from the second "
                                + "drain on - a note redelivered after a crashed drain compounding rather than "
                                + "converging, which is the only thing this leg is for"),
                new Mutation(Mutant.NO_WORK_AT_ALL, PublicationHookContract::recordsOnPublish,
                        "and the drained surface must be the converged one, or the two drains are being compared over "
                                + "a surface nothing delivered to")));
        mutations.put(Property.A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED, List.of(
                new Mutation(Mutant.A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG, PublicationHookContract::recordsOnPublish,
                        "a QUARANTINE legitimately fires the withhold-change feed, so the honest way for a held "
                                + "artifact to acquire a PUBLISH row is an observer that treats the two feeds as one. "
                                + "That is the smallest thing this check forbids, and it is invisible to any "
                                + "assertion that only counted rows")));

        // --- the publish interceptor ---------------------------------------------------------------------------------
        mutations.put(Property.THE_VERDICT_LEGS_RECEIVE_THE_PUBLICATIONS_OWN_SCOPED_STORE, List.of(
                new Mutation(Mutant.A_ROOT_SCOPED_RECORD,
                        "the half a provider owns: which store the three legs are HANDED is Publication's routing and "
                                + "no hook can change it, but whether the screen writes through the one it was handed "
                                + "is entirely the screen's. The mutation moves the row and nothing else - it is "
                                + "still written, still correct, still keyed the same way, one repository up (§6)")));
        mutations.put(Property.ACCEPT_IS_THE_NEUTRAL_ANSWER_AND_AN_EMPTY_CHAIN_ACCEPTS, List.of(
                new Mutation(Mutant.A_NON_NEUTRAL_DEFAULT,
                        "the clause's hook-facing half is that an UN-ARRANGED screen has nothing against the artifact "
                                + "and says so. QUARANTINE is the weakest answer that is not the neutral one, and a "
                                + "screen giving it holds every upload for a reason nobody stated")));
        mutations.put(Property.A_SCREEN_DOES_NOT_CATCH_ITS_OWN_STORE_FAILURE_INTO_AN_ACCEPT, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        fixture -> fixture instanceof Interceptor screen && !screen.reads().isEmpty(),
                        "the check's own preamble is that the screen really consults the keys it declares: the outage "
                                + "is armed on exactly those, so a screen that reads nothing has nothing to fail and "
                                + "satisfies the clause for free. This is the interceptor half's general vacuity "
                                + "probe, and it is the only place on this role where an inert screen is caught"),
                new Mutation(Mutant.A_BLIND_ACCEPT,
                        fixture -> fixture instanceof Interceptor screen && !screen.reads().isEmpty(),
                        "this is the one verdict-leg obligation that is genuinely per-implementation, so it is the "
                                + "one an implementation can quietly lose: a try/catch around its own read, "
                                + "answering the neutral value. The mutation is that catch, and nothing else. It is "
                                + "declared only for a screen that names a verdict-bearing read, because a screen "
                                + "that consults no state has no read to fail")));
        mutations.put(Property.WITHHELD_IS_A_PURE_READ_ON_EVERY_SERVE_AND_ENUMERATION, List.of(
                new Mutation(Mutant.A_WRITING_WITHHELD,
                        "the purity half is judged by comparing the store's keys either side of three serves and an "
                                + "enumeration, so the mutation is one key written on that path - a lazy refresh, an "
                                + "access counter, a memo persisted 'just this once' - which is what §10 forbids on a "
                                + "GET and what the comparison exists to see")));
        mutations.put(Property.A_LATER_VERDICT_RETRACTS_WITHOUT_A_POINTER_REWRITE, List.of(
                new Mutation(Mutant.A_LATCHED_WITHHELD,
                        PublicationHookContract::holdsOnTheReadSide,
                        "the retraction only works because the read side is RE-CONSULTED rather than latched at "
                                + "publish time, and the check proves it on the publication that has already been "
                                + "serving. A screen that memoised its first answer keeps serving what it served, "
                                + "which is the defect clause 9 names - and it is declared only for the screen whose "
                                + "verdict lives on the read side, since for the others the check drives the kit's "
                                + "own probe and the fixture's screen is a bystander")));
        mutations.put(Property.A_BYTE_IDENTICAL_REPLAY_REACHES_THE_SAME_VERDICT_AND_UPSERTS, List.of(
                new Mutation(Mutant.A_ROW_PER_DELIVERY,
                        "the upsert half: committed is called again on every replay, so a screen that appends grows "
                                + "its record once per attempt. One extra row from the second commit on is the "
                                + "smallest form of that, and it is precisely what a crashed-and-replayed publish "
                                + "produces in production")));
        mutations.put(Property.ONE_INSTANCE_SERVES_CONCURRENT_PUBLISHES_AND_READS, List.of(
                new Mutation(Mutant.A_VERDICT_REMEMBERED_IN_A_FIELD,
                        fixture -> fixture instanceof Interceptor screen && screen.verdicts().size() > 1,
                        "clause 1's defect is a screen keeping per-call state in a field, which answers for whichever "
                                + "artifact wrote the field last. The mutation is that, made deterministic - every "
                                + "publish gets the first one's verdict - because a check that only fails when the "
                                + "interleaving cooperates is not a check. Declared only for a screen that can reach "
                                + "more than one verdict, since otherwise every answer is the same answer and there "
                                + "is nothing to confuse")));
        mutations.put(Property.THE_COMMITTED_TO_VISIBILITY_CRASH_WINDOW_REPLAYS_CLEAN, List.of(
                new Mutation(Mutant.A_ROW_PER_DELIVERY,
                        "the hook-facing half of this window is that the replay REPAIRS it, and it only does so "
                                + "because committed upserts - the check says so out loud and then compares the "
                                + "record across the replay. A screen that appends turns the repair into an "
                                + "accumulation, one row per crashed attempt")));

        // --- the pre-commit release hook ------------------------------------------------------------------------------
        mutations.put(Property.A_RELEASE_HOOK_IS_NOT_A_CONTAINED_PUBLICATION_OBSERVER, List.of(
                new Mutation(Mutant.A_MISDERIVED_ROLE,
                        "the property is the structural claim itself, so the mutation is the registration accident it "
                                + "forbids: a hook that also answers PublicationObserver, which one `uses` clause "
                                + "would then discover into the contained after-commit path")));
        mutations.put(Property.A_THROWING_HOOK_PROPAGATES_AND_LEAVES_THE_HOLD_SAFE, List.of(
                new Mutation(Mutant.A_RELEASE_SURFACE_THAT_SWALLOWS,
                        "the propagation half: a surface that catches a hook's failure tells the reviewer the release "
                                + "landed, which is the fail-open direction the whole role exists to prevent"),
                new Mutation(Mutant.A_RELEASE_SURFACE_THAT_MUTATES_FIRST,
                        "and the safe-state half, which is a different claim and the weaker break: the failure still "
                                + "propagates, but the hold is already gone, so only a check that re-reads durable "
                                + "state afterwards can tell this surface from a correct one")));
        mutations.put(Property.HOOKS_THAT_RAN_BEFORE_THE_FAILURE_ARE_IDEMPOTENT_ON_RETRY, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        "THE instance this kit was carried here for: a hook that is a no-op throughout satisfies "
                                + "every step of this check - the poisoned fan-out still fails, the retry still "
                                + "completes, the third run still changes nothing - and the only assertion that can "
                                + "tell it apart is that the override was actually promoted")));
        mutations.put(Property.THE_RELEASE_IS_VISIBLE_ONLY_AFTER_EVERY_HOOK_SUCCEEDED, List.of(
                new Mutation(Mutant.A_RELEASE_SURFACE_THAT_MUTATES_FIRST,
                        "the property is an ORDERING, so the mutation is the ordering reversed and nothing else: "
                                + "every hook still runs, the failure still propagates, and the only difference is a "
                                + "half-released artifact serving while a remaining hold kind was never cleared")));
        mutations.put(Property.A_STORE_FAULT_MID_FAN_OUT_LEAVES_THE_HOLD_SAFE, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        "the same instance from the other side, and the sharper one: the outage is armed on exactly "
                                + "the namespaces the fixture declares, so a hook that writes nothing there cannot be "
                                + "made to fail and the leg observes a clean release it calls a safe hold")));
        mutations.put(Property.A_DISCARD_DROPS_THE_RECORD_WITHOUT_PROMOTING_AN_OVERRIDE, List.of(
                new Mutation(Mutant.NO_WORK_AT_ALL,
                        "the drop half: a discard that leaves the per-version record behind strands a thrown-away "
                                + "version's row forever, since no sweep will ever reach a version with no published "
                                + "sidecar"),
                new Mutation(Mutant.A_DISCARD_THAT_PROMOTES,
                        "and the asymmetry half, which is the whole point of the clause: a discard that promotes an "
                                + "override records that a human cleared a finding nobody looked at, and suppresses "
                                + "every future legitimate hold on those bytes")));
        mutations.put(Property.A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD, List.of(
                new Mutation(Mutant.A_HOOK_THAT_RAISES_FOR_AN_UNHELD_PATH,
                        "the property is what makes registering an unused hold kind harmless, so the mutation is the "
                                + "hook refusing a path it never held - which turns an installed-but-idle plugin into "
                                + "a deployment where no review can ever be released")));
        return Collections.unmodifiableMap(mutations);
    }

    /** The mutations {@code fixture}'s leg of {@code property} must fail against - every declared one whose shape
     *  this hook has. An empty answer means the property carries no falsification at all, which the census holds to a
     *  reviewed list. */
    public static List<Mutation> mutations(PublicationHookFixture fixture, Property property) {
        Objects.requireNonNull(fixture, "fixture");
        return mutations().getOrDefault(property, List.of()).stream()
                .filter(mutation -> mutation.appliesTo().test(fixture))
                .toList();
    }

    /**
     * Whether this screen expresses its verdict on the <em>read</em> side - a retroactive hold that lets the publish
     * through and retracts it later - derived from the two declarations a fixture already makes rather than from a
     * third one invented for the falsification leg: it consults stored state ({@link Interceptor#reads()} is
     * non-empty) yet can reach no verdict but the neutral one at publish time ({@link Interceptor#verdicts()} is
     * exactly {@code ACCEPT}). A screen that votes at publish time reaches a non-neutral verdict; a screen that
     * consults nothing has no side to hold on.
     */
    public static boolean holdsOnTheReadSide(PublicationHookFixture fixture) {
        return fixture instanceof Interceptor screen
                && !screen.reads().isEmpty()
                && screen.verdicts().equals(Set.of(PublishInterceptor.Disposition.ACCEPT));
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
                    + Delivery.DURABLE_AFTER_ENQUEUE + ", not at-least-once observation. Only the earlier pre-commit "
                    + "intent/state machine, proven at every injected crash point, could raise the class - and "
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
    static boolean owned(PublicationHookFixture fixture, String key) {
        return fixture.namespaces().stream().anyMatch(space -> key.equals(space) || key.startsWith(space + "/"));
    }

    /** The keys {@link Publication} itself owns - not the hook's, and not evidence of an escape. The withhold marker
     *  root is taken from {@link Withheld} rather than spelled out, so this stays one convention with one owner. */
    private static boolean isPublicationKey(String key) {
        return key.startsWith("blobs/") || key.startsWith(ServableNames.PUBLISHED + "/")
                || key.startsWith(Withheld.ROOT) || key.startsWith("gc/");
    }

    // --- shared drivers, so every check runs the one choreography ---------------------------------------------------

    /**
     * A publication over {@code hooks}, split into interceptors and observers exactly as {@link Publication} splits
     * its own discovered list - the kit never keeps a second opinion about which hook is which.
     *
     * <p>It is also where a {@link ChoreographyMutant} lands. Every check builds its publication here, and the
     * store it hands over is the one deployment object every check body already holds, so a choreography arranged on
     * the store reaches every publication without forty-six signatures learning about it. With no mutant armed - which
     * is every ordinary run - {@link ChoreographyMutant#NONE} hands the hooks straight through.
     */
    static Publication publication(ArtifactStore store, List<? extends PublicationObserver> hooks) {
        List<PublicationObserver> observers = FaultInjectingStore.choreographyOf(store).arrange(hooks);
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
                _ -> visibility(artifact));
    }

    /**
     * The visibility one kit publish declares: the request path, and - when the descriptor carries one - the
     * coordinate it stands for.
     *
     * <p>Every kit publish used to declare {@link Publication.Visibility#at} alone, which sets no {@code described},
     * so every artifact the kit committed reached an observer with no coordinate and no version. That is exactly the
     * shape a coordinate-keyed observer skips by design, so hooks like the search and event publication observers
     * could not be driven through the kit at all and their fixtures carried exclusions saying so. The refinement
     * seam already existed on the free {@code Visibility}; the kit simply never used it.
     *
     * <p>A path-only descriptor still declares a path-only visibility, so a fixture that wants the old shape - and
     * the checks about coordinate-less envelope paths, which are about exactly that - gets it by handing a
     * descriptor built with {@link #descriptor(String)}.
     */
    private static Publication.Visibility visibility(ArtifactDescriptor artifact) {
        Publication.Visibility at = Publication.Visibility.at(artifact.path());
        return artifact.coordinate() == null ? at : at.describing(artifact);
    }

    static ByteArrayInputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The contract properties nothing this kit can substitute falsifies, each with the reason - the reviewed list,
     * shared by both trees so the review is made once.
     *
     * <p>A property here is not exempt from being measured; it is a property whose negation cannot be EXPRESSED
     * through the seams the kit owns. Anything else on this list would be a claim rather than a finding, which is
     * why it holds exactly one entry.
     */
    public static Map<Property, String> unfalsifiable() {
        return Map.of(

                Property.THE_BLOB_TO_CHAIN_CRASH_WINDOW_LEAVES_ONLY_AN_UNREFERENCED_BLOB,
                "the chain never runs in this window - the crash is armed on the blob's own size read, before the first "
                        + "screen is asked - so the fixture's hook is not called and neither is any arrangement of the "
                        + "kit's own probes. There is nothing of the choreography left to remove: what the window leaves "
                        + "is Publication's and the store's, and falsifying it would mean mutating the product itself, "
                        + "which is a mechanism this kit deliberately does not own (see ChoreographyMutant).");
    }

    /**
     * The arrangement that falsifies each clause about {@code Publication}'s own commit choreography.
     *
     * <p>These clauses are not un-falsifiable, they are un-falsifiable-<em>by-a-hook</em>: no substitution of a
     * hook's own answers can make "the chain ran in ascending order" or "committed fired before the commit point"
     * come out false, because the behaviour belongs to the choreography around the hook rather than to the hook. The
     * mutation is applied to the arrangement instead, and {@link Falsification#requireBrokenByChoreography} requires
     * the clause to say otherwise.
     *
     * <p>It lives here rather than in a driver because BOTH trees must falsify these clauses over their own
     * population - the core's archetypes, and the fourteen hooks that actually ship - and a pairing declared
     * twice is a pairing that can disagree with itself.
     */
    public static Map<Property, ChoreographyMutant> choreography() {
        return Map.ofEntries(
                Map.entry(Property.AN_ERROR_ESCAPES_THE_OBSERVER_CONTAINMENT,
                        ChoreographyMutant.A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE),
                Map.entry(Property.THE_WITHHOLD_FEED_FIRES_ONLY_ON_A_DURABLE_TRANSITION,
                        ChoreographyMutant.A_WITHHOLD_FEED_THAT_FIRES_TWICE),
                Map.entry(Property.EVERY_SCREEN_IN_THE_CHAIN_PARTICIPATES,
                        ChoreographyMutant.A_CHAIN_THAT_ASKS_A_REPEATED_SCREEN_ONCE),
                Map.entry(Property.THE_CONTENT_VIEW_RESTREAMS_THE_BLOB_UNDER_TWO_DIFFERENT_BOUNDS,
                        ChoreographyMutant.A_CONTENT_VIEW_THAT_IGNORES_THE_CALLERS_BOUND),
                Map.entry(Property.A_THROWING_ASSESS_FAILS_THE_PUBLISH_WITH_NO_POINTER_LINKED,
                        ChoreographyMutant.A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE),
                Map.entry(Property.A_THROWING_COMMITTED_FAILS_THE_PUBLISH,
                        ChoreographyMutant.A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE),
                Map.entry(Property.A_THROWING_WITHHELD_FAILS_THE_READ_CLOSED,
                        ChoreographyMutant.A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE),
                Map.entry(Property.AN_ERROR_ESCAPES_BOTH_SIDES_OF_THE_CONTAINMENT,
                        ChoreographyMutant.A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE),
                Map.entry(Property.THE_INHERITED_OBSERVER_LEGS_STAY_CONTAINED,
                        ChoreographyMutant.AN_OBSERVER_FAILURE_THAT_STOPS_THE_FAN_OUT),
                Map.entry(PublicationHookContract.Property
                                .THE_DISCOVERED_CHAIN_IS_CACHED_AND_AN_INJECTED_ONE_IS_SORTED_PER_CONSTRUCTION,
                        ChoreographyMutant.A_CHAIN_IN_THE_ORDER_IT_WAS_GIVEN),
                Map.entry(Property.THE_CHAIN_RUNS_IN_ASCENDING_ORDER_AND_THE_STRONGEST_DISPOSITION_ROUTES,
                        ChoreographyMutant.A_CHAIN_IN_THE_ORDER_IT_WAS_GIVEN),
                Map.entry(Property.ASSESS_IS_NOT_SHORT_CIRCUITED_BY_A_REJECT,
                        ChoreographyMutant.A_CHAIN_THAT_STOPS_AT_THE_FIRST_REJECT),
                Map.entry(Property.WITHHELD_IS_SHORT_CIRCUITED_ON_THE_FIRST_TRUE,
                        ChoreographyMutant.A_WITHHELD_THAT_ASKS_EVERY_SCREEN),
                Map.entry(Property.COMMITTED_FIRES_FOR_EVERY_DISPOSITION_OVER_THE_WHOLE_CHAIN,
                        ChoreographyMutant.A_COMMITTED_THAT_SKIPS_THE_NEUTRAL_VERDICT),
                Map.entry(Property.THE_CHAIN_IS_AWAITED_IN_FULL_AND_NEVER_ABANDONED_PART_WAY,
                        ChoreographyMutant.A_CHAIN_ABANDONED_AFTER_THE_FIRST_SCREEN),
                Map.entry(Property.STORE_THEN_GATE_LINKS_NO_POINTER_BEFORE_THE_CHAIN_VOTED,
                        ChoreographyMutant.A_POINTER_LINKED_BEFORE_THE_CHAIN_VOTES),
                Map.entry(Property.COMMITTED_FIRES_BEFORE_THE_COMMIT_POINT_SO_ACCEPT_IS_NOT_VISIBILITY,
                        ChoreographyMutant.A_POINTER_LINKED_BEFORE_THE_CHAIN_VOTES),
                Map.entry(Property.A_QUARANTINE_REVIEW_POINTER_IS_WRITTEN_BEFORE_COMMITTED_FIRES,
                        ChoreographyMutant.A_REVIEW_POINTER_REMOVED_BEFORE_COMMITTED),
                Map.entry(Property.THE_QUARANTINE_POINTER_TO_COMMITTED_CRASH_WINDOW_REPLAYS_CLEAN,
                        ChoreographyMutant.A_REVIEW_POINTER_REMOVED_BEFORE_COMMITTED));
    }

    /**
     * Whether this fixture's surface actually MOVES on a plain publish, computed from its own declared convergence.
     *
     * <p>An observer that records on the withhold leg or the delete leg alone - a hold-release recorder, a
     * retraction index - declares an empty converged view for a published artifact. Every publish-shaped check
     * then compares one empty view against another, and a publish-shaped mutation is invisible to that comparison
     * <em>by construction</em> rather than tolerated by it: the check would read the same whether the hook ran or
     * not. Declaring the mutation inapplicable there is the argument the falsification leg's failure message asks
     * for, and it is computed from the fixture rather than listed by hook name, so a hook that starts recording on
     * publish is measured from that day on without anyone remembering to strike it off a list.
     */
    private static boolean recordsOnPublish(PublicationHookFixture fixture) {
        return fixture instanceof PublicationHookFixture.Observer observer
                && !observer.converged(List.of(descriptor("/kit/applicability-probe"))).isEmpty();
    }

    public static ArtifactDescriptor descriptor(String path) {
        return ArtifactDescriptor.at("kit", path);
    }

    /**
     * A descriptor that names the coordinate its path stands for, for the checks and fixtures that need an observer
     * keyed on the neutral ecosystem/coordinate/version triple to see anything at all.
     *
     * <p>{@link #descriptor(String)} deliberately remains coordinate-less: a path-only publish is a real shape the
     * contract has properties about, and it is what a format that lays out an envelope produces. This is the other
     * one, and until it existed the kit could only produce the first - so a coordinate-keyed observer was
     * unreachable through the kit and its fixture had to say so in an exclusion rather than in a check.
     */
    public static ArtifactDescriptor coordinated(String path, String coordinate, String version) {
        return new ArtifactDescriptor("kit", coordinate, version, path, null, false, null, -1L);
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
