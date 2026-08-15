package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.PublishInterceptor.Disposition;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Interceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture.ObserverLeg;

import static build.jenesis.repository.store.testkit.PublicationHookContract.BODY;
import static build.jenesis.repository.store.testkit.PublicationHookContract.bytes;
import static build.jenesis.repository.store.testkit.PublicationHookContract.commit;
import static build.jenesis.repository.store.testkit.PublicationHookContract.descriptor;
import static build.jenesis.repository.store.testkit.PublicationHookContract.equal;
import static build.jenesis.repository.store.testkit.PublicationHookContract.failure;
import static build.jenesis.repository.store.testkit.PublicationHookContract.isTrue;
import static build.jenesis.repository.store.testkit.PublicationHookContract.keys;
import static build.jenesis.repository.store.testkit.PublicationHookContract.publication;
import static build.jenesis.repository.store.testkit.PublicationHookContract.thrownBy;

/**
 * The verdict-bearing half of the publication-hook contract: the thirteen {@link PublishInterceptor} clauses, driven
 * through {@link Publication#commit} - the one hosted-publish choreography and the only commit point.
 *
 * <p><b>Everything here is fail-closed, and two of the clauses are counter-intuitive enough that a kit written from a
 * plausible reading gets them backwards.</b> {@code assess} is <em>not</em> short-circuited by a {@code REJECT} - every
 * screen is still asked, so a screen that records what it saw sees every artifact - while {@code withheld}
 * <em>is</em>, the first {@code true} winning. And the containment is of {@code Exception}, so an {@link Error}
 * escapes on <em>either</em> side of the one-class-two-failure-modes split. Each of those is its own check.
 *
 * <p><b>The chain-level clauses are driven with the fixture's own screen in the chain</b>, beside kit-owned probes, so
 * a provider is really on the path rather than being asserted about in the abstract. What only a fixture can supply is
 * how to make its screen reach a verdict - a real gate votes on state, not on a constructor argument - and which store
 * keys its verdict reads, which is what lets the kit fault exactly those and prove the screen does not catch its own
 * store failure into a default {@code ACCEPT}.
 *
 * <p><b>One store-SPI hazard the kit cannot fix and therefore names.</b> {@link ArtifactStore#exists} does not throw:
 * a backend outage answers {@code false}, indistinguishable from "absent". A screen that keys its verdict or its
 * {@code withheld} probe on {@code exists} alone therefore <em>cannot</em> fail closed - the outage reads as "nothing
 * against it" - and will fail
 * {@link PublicationHookContract.Property#A_SCREEN_DOES_NOT_CATCH_ITS_OWN_STORE_FAILURE_INTO_AN_ACCEPT}. That is the
 * correct outcome: the fix is to key the probe on {@code readVersioned}, which propagates.
 */
final class InterceptorContract {

    /** A sibling large enough to exceed {@link PublishInterceptor.Content#LARGEST_SIBLING}, so clause 5's two bounds
     *  are told apart on a real over-ceiling companion rather than on a hypothetical. */
    private static final int OVER_CEILING = PublishInterceptor.Content.LARGEST_SIBLING + 1024;

    private InterceptorContract() {
    }

    static void checks(List<PublicationHookContract.Check> checks) {
        add(checks, PublicationHookContract.Property.ACCEPT_IS_THE_NEUTRAL_ANSWER_AND_AN_EMPTY_CHAIN_ACCEPTS,
                "ACCEPT is the neutral answer, never null, and the shipped empty chain accepts everything",
                InterceptorContract::acceptIsTheNeutralAnswer);
        add(checks, PublicationHookContract.Property.EVERY_SCREEN_IN_THE_CHAIN_PARTICIPATES,
                "the chain is additive: every screen participates and nothing selects one out",
                InterceptorContract::everyScreenParticipates);
        add(checks, PublicationHookContract.Property.THE_CONTENT_VIEW_RESTREAMS_THE_BLOB_UNDER_TWO_DIFFERENT_BOUNDS,
                "the content view re-streams the stored blob and its two sibling reads answer a bound differently",
                InterceptorContract::theContentViewRestreamsUnderTwoBounds);
        add(checks, PublicationHookContract.Property.THE_VERDICT_LEGS_RECEIVE_THE_PUBLICATIONS_OWN_SCOPED_STORE,
                "assess, committed and withheld all receive the one doubly-scoped store the publication routed through",
                InterceptorContract::theVerdictLegsReceiveTheScopedStore);
        add(checks, PublicationHookContract.Property.A_THROWING_ASSESS_FAILS_THE_PUBLISH_WITH_NO_POINTER_LINKED,
                "a throwing assess fails the publish and links no pointer of any kind",
                InterceptorContract::aThrowingAssessFailsThePublish);
        add(checks, PublicationHookContract.Property.A_THROWING_COMMITTED_FAILS_THE_PUBLISH,
                "a throwing committed fails the publish too - it is a verdict leg, not an observer leg",
                InterceptorContract::aThrowingCommittedFailsThePublish);
        add(checks, PublicationHookContract.Property.A_THROWING_WITHHELD_FAILS_THE_READ_CLOSED,
                "a throwing withheld fails the read closed rather than serving a path the chain could not clear",
                InterceptorContract::aThrowingWithheldFailsTheReadClosed);
        add(checks, PublicationHookContract.Property.AN_ERROR_ESCAPES_BOTH_SIDES_OF_THE_CONTAINMENT,
                "an Error escapes the inherited observer leg although an IOException on the same leg is contained",
                InterceptorContract::anErrorEscapesBothSides);
        add(checks, PublicationHookContract.Property.A_SCREEN_DOES_NOT_CATCH_ITS_OWN_STORE_FAILURE_INTO_AN_ACCEPT,
                "the screen's own store failure fails the write rather than degrading to a default ACCEPT",
                InterceptorContract::aScreenDoesNotCatchItsOwnStoreFailure);
        add(checks, PublicationHookContract.Property.THE_INHERITED_OBSERVER_LEGS_STAY_CONTAINED,
                "the observer legs a screen inherits stay contained while its verdict legs propagate",
                InterceptorContract::theInheritedObserverLegsStayContained);
        add(checks, PublicationHookContract.Property.WITHHELD_IS_A_PURE_READ_ON_EVERY_SERVE_AND_ENUMERATION,
                "withheld is consulted on every serve and every enumeration, and writes nothing",
                InterceptorContract::withheldIsAPureReadOnEveryRead);
        add(checks, PublicationHookContract.Property.A_LATER_VERDICT_RETRACTS_WITHOUT_A_POINTER_REWRITE,
                "a verdict that changes after the fact retracts a linked artifact without touching its pointer",
                InterceptorContract::aLaterVerdictRetractsWithoutAPointerRewrite);
        add(checks, PublicationHookContract.Property
                        .THE_DISCOVERED_CHAIN_IS_CACHED_AND_AN_INJECTED_ONE_IS_SORTED_PER_CONSTRUCTION,
                "an injected chain is sorted on every construction, whatever order the embedder handed it in",
                InterceptorContract::anInjectedChainIsSortedPerConstruction);
        add(checks, PublicationHookContract.Property.THE_CHAIN_RUNS_IN_ASCENDING_ORDER_AND_THE_STRONGEST_DISPOSITION_ROUTES,
                "the chain runs in ascending order and the strongest disposition routes the publication",
                InterceptorContract::ascendingOrderAndStrongestDisposition);
        add(checks, PublicationHookContract.Property.ASSESS_IS_NOT_SHORT_CIRCUITED_BY_A_REJECT,
                "assess is NOT short-circuited: every screen is asked even after one has answered REJECT",
                InterceptorContract::assessIsNotShortCircuited);
        add(checks, PublicationHookContract.Property.WITHHELD_IS_SHORT_CIRCUITED_ON_THE_FIRST_TRUE,
                "withheld IS short-circuited: the first screen answering true wins and the rest are never asked",
                InterceptorContract::withheldIsShortCircuited);
        add(checks, PublicationHookContract.Property.COMMITTED_FIRES_FOR_EVERY_DISPOSITION_OVER_THE_WHOLE_CHAIN,
                "committed fires over the whole chain, in order, for every disposition including ACCEPT",
                InterceptorContract::committedFiresForEveryDisposition);
        add(checks, PublicationHookContract.Property.A_BYTE_IDENTICAL_REPLAY_REACHES_THE_SAME_VERDICT_AND_UPSERTS,
                "a byte-identical replay runs the whole chain again, reaches the same verdict and upserts",
                InterceptorContract::aByteIdenticalReplayReachesTheSameVerdict);
        add(checks, PublicationHookContract.Property.ONE_INSTANCE_SERVES_CONCURRENT_PUBLISHES_AND_READS,
                "one instance serves concurrent publishes and reads without keeping per-call state in a field",
                InterceptorContract::oneInstanceServesConcurrentPublishesAndReads);
        add(checks, PublicationHookContract.Property.THE_CHAIN_IS_AWAITED_IN_FULL_AND_NEVER_ABANDONED_PART_WAY,
                "a slow screen is awaited in full: there is no timeout and no partial chain",
                InterceptorContract::theChainIsAwaitedInFull);
        add(checks, PublicationHookContract.Property.STORE_THEN_GATE_LINKS_NO_POINTER_BEFORE_THE_CHAIN_VOTED,
                "store-then-gate: the screen sees a stored blob and no pointer exists before the chain has voted",
                InterceptorContract::storeThenGateLinksNoPointerFirst);
        add(checks, PublicationHookContract.Property.A_QUARANTINE_REVIEW_POINTER_IS_WRITTEN_BEFORE_COMMITTED_FIRES,
                "a QUARANTINE's review pointer is already linked when committed fires",
                InterceptorContract::aQuarantinePointerPrecedesCommitted);
        add(checks, PublicationHookContract.Property.COMMITTED_FIRES_BEFORE_THE_COMMIT_POINT_SO_ACCEPT_IS_NOT_VISIBILITY,
                "committed fires before the layout and the commit point, so its ACCEPT is not a visibility claim",
                InterceptorContract::committedIsNotAVisibilityClaim);
        add(checks, PublicationHookContract.Property.THE_COMMITTED_TO_VISIBILITY_CRASH_WINDOW_REPLAYS_CLEAN,
                "a crash between committed and the visibility write leaves nothing served and replays clean",
                InterceptorContract::theCommittedToVisibilityCrashWindow);
        add(checks, PublicationHookContract.Property.THE_BLOB_TO_CHAIN_CRASH_WINDOW_LEAVES_ONLY_AN_UNREFERENCED_BLOB,
                "a crash between the blob write and the chain leaves an unreferenced blob and no verdict",
                InterceptorContract::theBlobToChainCrashWindow);
        add(checks, PublicationHookContract.Property.THE_QUARANTINE_POINTER_TO_COMMITTED_CRASH_WINDOW_REPLAYS_CLEAN,
                "a crash between the review pointer and committed leaves the hold standing and replays clean",
                InterceptorContract::theQuarantinePointerToCommittedCrashWindow);
    }

    private static void add(List<PublicationHookContract.Check> checks, PublicationHookContract.Property property,
                            String name, PublicationHookContract.Body body) {
        checks.add(new PublicationHookContract.Check(property, name, body));
    }

    // --- clauses 3 and 4: the neutral answer, and an additive chain -------------------------------------------------

    private static void acceptIsTheNeutralAnswer(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        isTrue(screen.verdicts().contains(Disposition.ACCEPT), fixture,
                "every screen has the neutral answer; a fixture that cannot reach ACCEPT has mis-declared its verdicts");

        // The shipped chain: no provider on the module path, so every upload is accepted, linked and served, and
        // Publication reduces to a plain content-addressed store.
        ArtifactDescriptor bare = descriptor("/kit/bare");
        Publication.Commit empty = commit(publication(store, List.of()), bare);
        equal(empty.disposition(), Disposition.ACCEPT, fixture, "an empty chain accepts");
        isTrue(empty.visible(), fixture, "and the artifact is linked and visible");

        // The fixture's screen, un-arranged: no state says anything against this artifact, so it answers the neutral
        // ACCEPT - and it answers something, because null is never a legal return.
        ArtifactDescriptor neutral = descriptor("/kit/neutral");
        Verdicts seen = new Verdicts();
        Publication.Commit committed = commit(publication(store, List.of(seen.watching(screen.create()))), neutral);
        equal(committed.disposition(), Disposition.ACCEPT, fixture,
                "an un-arranged screen has nothing against the artifact and answers the neutral ACCEPT");
        isTrue(!seen.verdicts.isEmpty(), fixture, "the screen really was asked");
        isTrue(!seen.verdicts.contains(null), fixture,
                "null is never a legal return from assess - Publication would fail on the comparison, and the "
                        + "'nothing against it' answer already exists");

        // The read side's own neutral answer: false means "serves".
        Publication publication = publication(store, List.of(screen.create()));
        equal(publication.located(neutral.path()).isPresent(), true, fixture,
                "and withheld answers false for 'serves', so the accepted artifact resolves to its blob");
        equal(publication.located("/kit/never-published").isPresent(), false, fixture,
                "while an unpublished path is empty rather than an exception");
    }

    private static void everyScreenParticipates(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        List<PublishInterceptor> chain = List.of(
                log.probe("a", 0, Disposition.ACCEPT), screen.create(), log.probe("b", 0, Disposition.ACCEPT),
                log.probe("c", 0, Disposition.ACCEPT));

        commit(publication(store, chain), descriptor("/kit/additive"));

        equal(log.assessed, List.of("a", "b", "c"), fixture,
                "every probe in the chain was asked - the policy is additive, there is nothing to select and so "
                        + "nothing to fail at resolution");
        equal(log.committed, List.of("a", "b", "c"), fixture, "and every one was notified of the outcome");

        // Additive to the point of no de-duplication: the same screen registered twice is asked twice. That is the
        // packaging guard this family does NOT have (it carries no name(), so it never rides Providers), and pinning
        // it here is what keeps a future "helpful" de-duplication from silently changing the contract.
        Sequence twice = new Sequence();
        PublishInterceptor duplicate = twice.probe("dup", 0, Disposition.ACCEPT);
        commit(publication(store, List.of(duplicate, duplicate)), descriptor("/kit/additive-twice"));
        equal(twice.assessed, List.of("dup", "dup"), fixture,
                "a screen registered twice is asked twice and nothing reports it - this family has no name() and so "
                        + "gets none of the shared provider packaging guards");
    }

    // --- clause 5: streaming, and two bounds that answer differently by design ---------------------------------------

    private static void theContentViewRestreamsUnderTwoBounds(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;

        // A small published companion, and one deliberately past the whole-document ceiling.
        commit(publication(store, List.of()), descriptor("/kit/pom"), "the-pom");
        byte[] fat = new byte[OVER_CEILING];
        for (int index = 0; index < fat.length; index++) {
            fat[index] = (byte) (index % 251);
        }
        commit(publication(store, List.of()), descriptor("/kit/fat"), new ByteArrayInputStream(fat));

        List<String> failures = new ArrayList<>();
        PublishInterceptor reader = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                // The screen is handed a re-stream of the STORED blob, never the upload's bytes - and a second open
                // is a fresh stream, so a screen that parses and re-reads never holds the artifact in memory.
                try (InputStream first = content.open(); InputStream second = content.open()) {
                    expect(failures, "the first open re-streams the stored blob",
                            BODY.equals(new String(first.readAllBytes(), StandardCharsets.UTF_8)));
                    expect(failures, "and a second open is a fresh stream over the same blob",
                            BODY.equals(new String(second.readAllBytes(), StandardCharsets.UTF_8)));
                }
                expect(failures, "a whole-document sibling read answers the document entire",
                        content.sibling("/kit/pom").map(body -> "the-pom".equals(
                                new String(body, StandardCharsets.UTF_8))).orElse(false));
                expect(failures, "an unpublished sibling is empty, never a zero-length body a caller would parse",
                        content.sibling("/kit/never").isEmpty() && content.sibling("/kit/never", 64).isEmpty());

                // The whole-document read THROWS past its ceiling: a prefix presented as whole is the
                // silently-incomplete answer, so the only honest outcome is a failure.
                expect(failures, "the whole-document read fails on an over-ceiling companion rather than "
                        + "returning a prefix", thrownBy(() -> content.sibling("/kit/fat")) instanceof IOException);

                // The bounded read NEVER fails on size, and the bound it honours is the CALLER's - here far above
                // the whole-document ceiling, which is exactly the case that used to divide the two ingress legs.
                Optional<Content.Bounded> whole = content.sibling("/kit/fat", OVER_CEILING);
                expect(failures, "the bounded read honours a limit above the whole-document ceiling",
                        whole.isPresent() && !whole.get().truncated() && whole.get().content().length == OVER_CEILING);
                Optional<Content.Bounded> cut = content.sibling("/kit/fat", 1024);
                expect(failures, "and reports an over-bound companion instead of failing on it",
                        cut.isPresent() && cut.get().truncated() && cut.get().content().length == 1024);
                Optional<Content.Bounded> exact = content.sibling("/kit/pom", "the-pom".length());
                expect(failures, "a sibling of exactly the limit is reported whole rather than pessimistically cut",
                        exact.isPresent() && !exact.get().truncated());
                return Disposition.ACCEPT;
            }
        };
        commit(publication(store, List.of(reader, screen.create())), descriptor("/kit/jar"));

        if (!failures.isEmpty()) {
            throw failure(fixture, "the Content view handed to a screen broke clause 5: " + failures);
        }
    }

    private static void expect(List<String> failures, String what, boolean held) {
        if (!held) {
            failures.add(what);
        }
    }

    // --- clause 6: one scoped store -----------------------------------------------------------------------------

    private static void theVerdictLegsReceiveTheScopedStore(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor fixtureScreen = (Interceptor) fixture;
        ArtifactStore scoped = store.scope("acme").scope("main");
        List<ArtifactStore> seen = new ArrayList<>();
        PublishInterceptor witness = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                seen.add(content.store());
                return Disposition.ACCEPT;
            }

            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store) {
                seen.add(store);
            }

            @Override
            public boolean withheld(String path, ArtifactStore store) {
                seen.add(store);
                return false;
            }
        };
        Publication publication = publication(scoped, List.of(witness, fixtureScreen.create()));
        commit(publication, descriptor("/kit/scoped"));
        publication.located("/kit/scoped");

        equal(seen.size(), 3, fixture, "all three verdict legs ran");
        for (ArtifactStore handed : seen) {
            isTrue(handed == scoped, fixture,
                    "Content.store(), the store handed to committed and the store handed to withheld are the ONE "
                            + "doubly-scoped view the publication routed through. A verdict recorded against any "
                            + "other store is a verdict recorded for the wrong repository.");
        }

        // ... and the screen USED it. The witness above proves what the three legs are handed; until D-135's
        // falsification leg went looking, nothing proved the fixture's own screen wrote through it - this check drove
        // a real screen under a real scope and then only ever read the kit's probe. A screen that recorded its
        // verdict against the deployment root instead puts one repository's quarantine decision in another
        // repository's key space (§6), reads as a perfectly correct row from everywhere but the scope it belongs in,
        // and passed every leg of this kit. It is the interceptor half's counterpart of the observer's
        // THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE, and it belongs on the clause that already had the scope
        // in its hands.
        isTrue(!fixtureScreen.projection(scoped).isEmpty(), fixture,
                "the screen recorded something inside the publication's own scope - without that the comparison "
                        + "below is true of a screen that recorded nothing at all, which is exactly the vacuity that "
                        + "hid this gap");
        equal(fixtureScreen.projection(store), Map.of(), fixture,
                "and nothing at all was recorded against the store one scope up. A verdict, an audit row or a seen "
                        + "marker written through the deployment root rather than through the store the leg was "
                        + "handed is another repository's data (§6)");
    }

    // --- clause 7: the reversal ---------------------------------------------------------------------------------

    private static void aThrowingAssessFailsThePublish(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        ArtifactDescriptor artifact = descriptor("/kit/unscreened");
        PublishInterceptor poison = log.probe("poison", 5, Disposition.ACCEPT);
        ((Probe) poison).assessFailure = new IOException("the gate could not render a verdict");
        Publication publication = publication(store,
                List.of(screen.create(), poison, log.probe("after", 10, Disposition.ACCEPT)));

        Throwable failed = thrownBy(() -> commit(publication, artifact));

        isTrue(failed instanceof IOException, fixture,
                "an exception out of assess fails the write: a gate that cannot render a verdict must never let an "
                        + "unscreened artifact through (was " + failed + ")");
        equal(publication.located(artifact.path()).isPresent(), false, fixture, "nothing serves at the path");
        equal(publication.blob(artifact.path()).isPresent(), false, fixture, "and no pointer was linked at all");
        equal(publication.blob("/quarantine" + artifact.path()).isPresent(), false, fixture,
                "not even a review pointer - the chain never reached a disposition to route by");
        equal(log.committed, List.of(), fixture,
                "and no screen was notified of an outcome, because there was no outcome");
        isTrue(store.delegate().exists("blobs/" + PublicationHookContract.hash(BODY)), fixture,
                "the content-addressed blob does remain, unreferenced, for garbage collection - store-then-gate "
                        + "means the body is always stored before it is judged");
    }

    private static void aThrowingCommittedFailsThePublish(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        ArtifactDescriptor artifact = descriptor("/kit/committed-throws");
        Probe poison = (Probe) log.probe("poison", 0, Disposition.ACCEPT);
        poison.committedFailure = new IOException("the audit sink is down");
        Publication publication = publication(store, List.of(screen.create(), poison));

        Throwable failed = thrownBy(() -> commit(publication, artifact));

        isTrue(failed instanceof IOException, fixture,
                "committed is a VERDICT leg, not an observer leg: a throw there fails the publish (was " + failed + ")");
        equal(publication.located(artifact.path()).isPresent(), false, fixture,
                "and leaves nothing servable, because committed fires before the layout and before the commit point");

        // The QUARANTINE leg, where clause 13 puts the review pointer INSIDE the chain run and therefore BEFORE
        // committed. So "leaves nothing servable" is exactly true of the publication's own path and no wider than
        // that: the review pointer the verdict already linked stands, and a reviewer still finds the held bytes -
        // which is the safe direction, since a lost hold is the disclosure and a stranded review pointer is not.
        ArtifactDescriptor held = descriptor("/kit/committed-throws-held");
        Sequence quarantine = new Sequence();
        Probe holder = (Probe) quarantine.probe("holder", -5, Disposition.QUARANTINE);
        Probe failing = (Probe) quarantine.probe("failing", 5, Disposition.ACCEPT);
        failing.committedFailure = new IOException("the quarantine audit sink is down");
        Publication quarantining = publication(store, List.of(holder, failing, screen.create()));

        isTrue(thrownBy(() -> commit(quarantining, held)) instanceof IOException, fixture,
                "a throwing committed fails a quarantined publish too");
        equal(quarantining.located(held.path()).isPresent(), false, fixture,
                "the artifact's own path serves nothing");
        equal(quarantining.blob("/quarantine" + held.path()).isPresent(), true, fixture,
                "while the review pointer written before committed fired stands - the hold survives the failure, "
                        + "which is the fail-closed direction, and it is what clause 13's ordering buys");
    }

    private static void aThrowingWithheldFailsTheReadClosed(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/unclearable");
        commit(publication(store, List.of(screen.create())), artifact);

        // A checked failure propagates: the read fails rather than serving a path the chain could not clear.
        Publication checked = publication(store, List.of(screen.create(), new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) throws IOException {
                throw new IOException("the hold record could not be read");
            }
        }));
        isTrue(thrownBy(() -> checked.located(artifact.path())) instanceof IOException, fixture,
                "a checked failure out of withheld fails the READ closed - serving a path whose hold could not be "
                        + "checked is the fail-open outcome this leg exists to prevent");
        isTrue(thrownBy(() -> new ServableNames(store, checked).state(artifact.path())) instanceof IOException, fixture,
                "and the enumeration seam propagates it identically, so a listing and a download can never disagree");

        // A RUNTIME failure reaches the same destination by the other route: ServableNames catches it and treats the
        // path as withheld, so one hostile name neither leaks nor 500s a whole listing.
        Publication hostile = publication(store, List.of(screen.create(), new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                throw new IllegalStateException("an encoding-hostile request path");
            }
        }));
        equal(new ServableNames(store, hostile).state(artifact.path()), ServableNames.State.WITHHELD, fixture,
                "a RuntimeException out of withheld is caught by ServableNames and read as WITHHELD - the same "
                        + "fail-closed direction reached by a different route");
        equal(hostile.located(artifact.path()).isPresent(), false, fixture,
                "so the serve path answers empty rather than throwing an InvalidPathException out of a GET");
    }

    private static void anErrorEscapesBothSides(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;

        // The contained side, first, so the contrast is not a coincidence: an IOException out of the inherited
        // observer leg is swallowed and the publish stands.
        ArtifactDescriptor contained = descriptor("/kit/leg-contained");
        Publication.Commit stands = commit(publication(store, List.of(observing(new IOException("webhook down")),
                screen.create())), contained);
        isTrue(stands.visible(), fixture, "an IOException out of the inherited onPublished is contained");

        // ... and the same leg with an Error escapes, because Publication catches `Exception`, not `Throwable`.
        ArtifactDescriptor escaping = descriptor("/kit/leg-error");
        Throwable failed = thrownBy(() -> commit(publication(store,
                List.of(observing(new StackOverflowError("blew the stack")), screen.create())), escaping));
        isTrue(failed instanceof StackOverflowError, fixture,
                "an Error out of the SAME leg escapes: the containment is of Exception, so one class has two failure "
                        + "modes keyed by method AND a third keyed by throwable kind (was " + failed + ")");

        // The verdict side needs no containment at all, so an Error escapes there too - stated so the two sides are
        // pinned together rather than one being inferred from the other.
        ArtifactDescriptor verdict = descriptor("/kit/verdict-error");
        Sequence log = new Sequence();
        Probe poison = (Probe) log.probe("poison", 0, Disposition.ACCEPT);
        poison.assessFailure = new StackOverflowError("blew the stack in assess");
        isTrue(thrownBy(() -> commit(publication(store, List.of(poison, screen.create())), verdict))
                instanceof StackOverflowError, fixture, "and an Error out of assess escapes the verdict leg too");
    }

    private static void aScreenDoesNotCatchItsOwnStoreFailure(PublicationHookFixture fixture,
                                                              FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        if (screen.reads().isEmpty()) {
            // A screen that consults no state cannot degrade to ACCEPT on a store failure - there is no read to fail.
            // Assert the shape rather than passing silently, so an implementation that GROWS a read without declaring
            // it is caught by the mismatch instead of quietly skipping this clause.
            isTrue(screen.verdicts().equals(Set.of(Disposition.ACCEPT)), fixture,
                    "a screen that declares no verdict-bearing read must also declare no verdict but ACCEPT: a "
                            + "QUARANTINE or REJECT has to come from somewhere, and if it comes from stored state the "
                            + "fixture must name the keys so this clause can be driven");
            return;
        }
        boolean driven = false;

        // (a) the assess leg, when this screen can reach a non-neutral verdict from stored state.
        Optional<Disposition> wanted = screen.verdicts().stream()
                .filter(verdict -> verdict != Disposition.ACCEPT).findFirst();
        if (wanted.isPresent()) {
            ArtifactDescriptor artifact = descriptor("/kit/blind-screen");
            screen.arrange(store, artifact, wanted.get());
            blind(store, screen);
            Throwable failed = thrownBy(() -> commit(publication(store, List.of(screen.create())), artifact));
            store.heal();

            isTrue(failed instanceof IOException, fixture,
                    "the screen's own store went away while it was rendering a verdict, and it answered anyway. A "
                            + "screen must NOT catch its own store failure into a default ACCEPT: 'I could not check' "
                            + "and 'nothing against it' are opposite answers, and the whole point of the "
                            + "sub-interface is that the first one fails the write (was " + failed + "). Note that "
                            + "ArtifactStore.exists answers false rather than throwing, so a probe keyed on it "
                            + "cannot fail closed at all - key it on readVersioned instead.");
            equal(publication(store, List.of()).blob(artifact.path()).isPresent(), false, fixture,
                    "and nothing was linked while the gate was blind");
            driven = true;
        }

        // (b) the withheld leg, when this screen has a read side. Same rule, opposite direction: a probe that cannot
        //     be answered must hide the path, never serve it.
        ArtifactDescriptor served = descriptor("/kit/blind-read");
        commit(publication(store, List.of(screen.create())), served);
        if (screen.arrangeWithhold(store, served.path())) {
            Publication publication = publication(store, List.of(screen.create()));
            equal(publication.located(served.path()).isPresent(), false, fixture,
                    "the arranged hold retracts the path while the store is healthy");
            blind(store, screen);
            Throwable failed = thrownBy(() -> publication.located(served.path()));
            store.heal();

            isTrue(failed instanceof IOException, fixture,
                    "and when the screen's own hold record cannot be read, the READ fails closed rather than "
                            + "serving: an unanswerable withhold probe is not a 'serves' (was " + failed + ")");
            driven = true;
        }

        isTrue(driven, fixture,
                "the fixture declares verdict-bearing reads " + screen.reads() + " but neither a non-ACCEPT verdict "
                        + "nor a read side, so this clause could not be driven at all");
    }

    /** Fault exactly the keys a screen says its verdict reads, on every store operation that can report a failure.
     *  {@code exists} and {@code list} are deliberately not armed: they answer {@code false} / empty rather than
     *  throwing, so arming them would not simulate an outage a screen could ever notice. */
    private static void blind(FaultInjectingStore store, Interceptor screen) {
        for (String prefix : screen.reads()) {
            Predicate<String> keys = FaultInjectingStore.keyPrefix(prefix);
            store.failEveryOn(FaultInjectingStore.Op.READ_VERSIONED, keys);
            store.failEveryOn(FaultInjectingStore.Op.OPEN, keys);
            store.failEveryOn(FaultInjectingStore.Op.READ, keys);
            store.failEveryOn(FaultInjectingStore.Op.SIZE, keys);
        }
    }

    private static void theInheritedObserverLegsStayContained(PublicationHookFixture fixture,
                                                              FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        List<String> reached = new ArrayList<>();
        ArtifactDescriptor artifact = descriptor("/kit/inherited");
        Publication publication = publication(store, List.of(
                observing(new IOException("the replication target is down")), screen.create(),
                (PublicationObserver) (published, _) -> reached.add(published.path())));

        Publication.Commit committed = commit(publication, artifact);

        isTrue(committed.visible(), fixture,
                "the observer leg an interceptor inherits is contained exactly as any observer's is - one class, two "
                        + "failure modes, keyed by method");
        equal(publication.located(artifact.path()).isPresent(), true, fixture, "the publish stands");
        equal(reached, List.of(artifact.path()), fixture, "and the observers behind the failing one still run");

        // And the default is a no-op, so a screen observes an accepted publish only when it says so: the empty
        // override is what keeps a screen from being double-counted as an observer of its own verdict.
        Set<ObserverLeg> overridden = ObserverLeg.overriddenBy(screen.create());
        isTrue(!overridden.contains(ObserverLeg.ON_PUBLISHED) || committed.visible(), fixture,
                "a screen that DOES override onPublished takes that leg's contained delivery in exchange for "
                        + "learning that the artifact really serves");
    }

    // --- clauses 8 and 9: the read side --------------------------------------------------------------------------

    private static void withheldIsAPureReadOnEveryRead(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/read-side");
        commit(publication(store, List.of(screen.create())), artifact);

        AtomicInteger asked = new AtomicInteger();
        Publication publication = publication(store, List.of(screen.create(), new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                asked.incrementAndGet();
                return false;
            }
        }));
        List<String> before = keys(store);

        publication.located(artifact.path());
        publication.located(artifact.path());
        publication.located(artifact.path());
        equal(asked.get(), 3, fixture,
                "withheld is asked on EVERY serve rather than latched at publish time - a screen that memoised its "
                        + "own answer for the process lifetime would break the retraction clause 9 promises");

        new ServableNames(store, publication).disclosable(artifact.path(), ServableNames.Policy.HIDE_WITHHELD);
        equal(asked.get(), 4, fixture, "and again on the enumeration seam, so a listing and a download agree");

        equal(keys(store), before, fixture,
                "and the read side wrote nothing: withheld renders durably stored state only - no lazy refresh, no "
                        + "write, no upstream fetch");
    }

    private static void aLaterVerdictRetractsWithoutAPointerRewrite(PublicationHookFixture fixture,
                                                                    FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/retracted");
        commit(publication(store, List.of(screen.create())), artifact);

        Publication serving = publication(store, List.of(screen.create()));
        equal(serving.located(artifact.path()).isPresent(), true, fixture, "the artifact has served for a while");
        String pointer = serving.blob(artifact.path()).orElseThrow(
                () -> failure(fixture, "the publish linked no pointer"));

        // The verdict changes after the fact - a new advisory against something that has served for months - and the
        // fixture's own read side is what expresses it, when it has one.
        boolean own = screen.arrangeWithhold(store, artifact.path());
        Publication after = own
                ? publication(store, List.of(screen.create()))
                : publication(store, List.of(screen.create(), new PublishInterceptor() {
                    @Override
                    public boolean withheld(String path, ArtifactStore store) {
                        return path.equals(artifact.path());
                    }
                }));

        equal(after.located(artifact.path()).isPresent(), false, fixture,
                "the already-linked artifact is retracted from serving at the very next read");
        equal(new ServableNames(store, after).state(artifact.path()), ServableNames.State.WITHHELD, fixture,
                "and from every enumeration surface, as WITHHELD rather than as absent");
        equal(after.blob(artifact.path()), Optional.of(pointer), fixture,
                "with no sweep and no pointer rewrite: the pointer is exactly what it was, which is what makes the "
                        + "retraction reversible");
        equal(serving.located(artifact.path()).isPresent(), own ? false : true, fixture,
                own
                        ? "a screen whose own read side was arranged retracts for every publication over that store"
                        : "while a publication whose chain does not carry the probe still serves - the verdict is the "
                                + "chain's, not the store's");
    }

    // --- clauses 10 and 11: lifecycle and ordering ----------------------------------------------------------------

    private static void anInjectedChainIsSortedPerConstruction(PublicationHookFixture fixture,
                                                               FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        for (int round = 0; round < 2; round++) {
            Sequence log = new Sequence();
            List<PublishInterceptor> unsorted = List.of(
                    log.probe("last", 10, Disposition.ACCEPT), screen.create(),
                    log.probe("first", -10, Disposition.ACCEPT), log.probe("middle", 0, Disposition.ACCEPT));
            commit(publication(store, unsorted), descriptor("/kit/sorted-" + round));
            equal(log.assessed, List.of("first", "middle", "last"), fixture,
                    "a chain injected by an embedder is sorted on EVERY construction (round " + round + "), so two "
                            + "publications built from the same list can never disagree about order");
        }
    }

    private static void ascendingOrderAndStrongestDisposition(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        Publication publication = publication(store, List.of(
                log.probe("late", 20, Disposition.ACCEPT), log.probe("early", -20, Disposition.QUARANTINE),
                screen.create(), log.probe("mid", 0, Disposition.ACCEPT)));

        Publication.Commit committed = commit(publication, descriptor("/kit/ordered"));

        equal(log.assessed, List.of("early", "mid", "late"), fixture, "the chain runs in ascending order()");
        equal(committed.disposition(), Disposition.QUARANTINE, fixture,
                "and the STRONGEST disposition across the chain routes the publication, whatever position voted it - "
                        + "the enum is declared weakest-to-strongest so 'strongest' is its natural order");

        // Order-independence of the collective verdict: the same votes in the opposite chain order route identically.
        Sequence reversed = new Sequence();
        Publication.Commit again = commit(publication(store, List.of(
                reversed.probe("early", -20, Disposition.ACCEPT), screen.create(),
                reversed.probe("late", 20, Disposition.QUARANTINE))), descriptor("/kit/ordered-reverse"));
        equal(again.disposition(), Disposition.QUARANTINE, fixture, "the collective verdict is order-independent");
    }

    private static void assessIsNotShortCircuited(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        Publication publication = publication(store, List.of(
                log.probe("rejecting", -10, Disposition.REJECT), screen.create(),
                log.probe("recorder", 0, Disposition.ACCEPT), log.probe("auditor", 10, Disposition.ACCEPT)));

        Publication.Commit committed = commit(publication, descriptor("/kit/not-short-circuited"));

        equal(committed.disposition(), Disposition.REJECT, fixture, "the rejection routes the publication");
        equal(log.assessed, List.of("rejecting", "recorder", "auditor"), fixture,
                "and EVERY screen after it was still asked. assess is not short-circuited by a REJECT - that is what "
                        + "lets a screen that records what it saw see every artifact, including the rejected ones. A "
                        + "kit that assumed the plausible early exit would have asserted the opposite and passed.");
        equal(log.committed, List.of("rejecting", "recorder", "auditor"), fixture,
                "and every one was told the outcome");
    }

    private static void withheldIsShortCircuited(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/short-circuited");
        commit(publication(store, List.of(screen.create())), artifact);

        AtomicInteger behind = new AtomicInteger();
        Publication publication = publication(store, List.of(new PublishInterceptor() {
            @Override
            public int order() {
                return -10;
            }

            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return true;
            }
        }, screen.create(), new PublishInterceptor() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public boolean withheld(String path, ArtifactStore store) {
                behind.incrementAndGet();
                return false;
            }
        }));

        equal(publication.located(artifact.path()).isPresent(), false, fixture, "the first true withholds the path");
        equal(behind.get(), 0, fixture,
                "and the screens behind it were never asked. withheld IS short-circuited - the opposite of assess - "
                        + "so a screen must never rely on being asked, and must not use the call as its own audit "
                        + "trail of what was read.");
    }

    private static void committedFiresForEveryDisposition(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        for (Disposition disposition : Disposition.values()) {
            Sequence log = new Sequence();
            Publication.Commit committed = commit(publication(store, List.of(
                    log.probe("first", -10, Disposition.ACCEPT), screen.create(),
                    log.probe("voter", 0, disposition), log.probe("last", 10, Disposition.ACCEPT))),
                    descriptor("/kit/committed-" + disposition.name().toLowerCase(Locale.ROOT)));

            equal(committed.disposition(), disposition, fixture, "the chain routed to " + disposition);
            equal(log.committed, List.of("first", "voter", "last"), fixture,
                    "committed fires over the WHOLE chain, in the same ascending order, for " + disposition
                            + " - including the screens that voted ACCEPT, which is what makes it an outcome "
                            + "notification rather than a verdict acknowledgement");
            equal(log.outcomes, List.of(disposition, disposition, disposition), fixture,
                    "and every screen is told the collective outcome, not its own vote");
        }
    }

    // --- clauses 1, 2 and 12 --------------------------------------------------------------------------------------

    private static void aByteIdenticalReplayReachesTheSameVerdict(PublicationHookFixture fixture,
                                                                  FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Disposition wanted = screen.verdicts().stream().filter(verdict -> verdict != Disposition.ACCEPT)
                .findFirst().orElse(Disposition.ACCEPT);
        ArtifactDescriptor artifact = descriptor("/kit/replayed-verdict");
        screen.arrange(store, artifact, wanted);

        Publication.Commit first = commit(publication(store, List.of(screen.create())), artifact);
        Map<String, String> once = screen.projection(store);
        Publication.Commit second = commit(publication(store, List.of(screen.create())), artifact);
        Map<String, String> twice = screen.projection(store);
        commit(publication(store, List.of(screen.create())), artifact);

        equal(second.disposition(), first.disposition(), fixture,
                "a byte-identical re-commit - the replay that repairs a first attempt that crashed mid-layout - runs "
                        + "the whole chain again and must reach the SAME disposition, or the repair converges on a "
                        + "different verdict than the attempt it repairs");
        equal(first.disposition(), wanted, fixture, "and the arranged verdict really was reached");
        equal(twice, once, fixture,
                "and committed upserted rather than appending or incrementing: it is called again on every replay");
        equal(screen.projection(store), once, fixture, "a third replay changes nothing either");
    }

    private static void oneInstanceServesConcurrentPublishesAndReads(PublicationHookFixture fixture,
                                                                     FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        PublishInterceptor shared = screen.create();      // ONE instance, as ServiceLoader discovery hands one out
        Publication publication = publication(store, List.of(shared));
        int concurrency = 8;
        List<ArtifactDescriptor> artifacts = new ArrayList<>();
        for (int index = 0; index < concurrency; index++) {
            artifacts.add(descriptor("/kit/concurrent-" + index));
        }
        // Half the artifacts are arranged to a non-neutral verdict, so a screen that kept per-call state in a field
        // would hand one publish another's answer rather than merely running slowly.
        Disposition wanted = screen.verdicts().stream().filter(verdict -> verdict != Disposition.ACCEPT)
                .findFirst().orElse(Disposition.ACCEPT);
        for (int index = 0; index < concurrency; index += 2) {
            screen.arrange(store, artifacts.get(index), wanted);
        }
        commit(publication, descriptor("/kit/concurrent-read"));

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Map<String, Disposition> reached = new ConcurrentHashMap<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(concurrency)) {
            for (int index = 0; index < concurrency; index++) {
                ArtifactDescriptor artifact = artifacts.get(index);
                workers.execute(() -> {
                    try {
                        reached.put(artifact.path(),
                                commit(publication, artifact, BODY + artifact.path()).disposition());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
                workers.execute(() -> {
                    try {
                        publication.located("/kit/concurrent-read");
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
        }

        equal(failures.stream().map(Object::toString).toList(), List.of(), fixture,
                "one discovered instance serves every publishing thread AND every reading thread - withheld rides "
                        + "the serve path, so this type is not only on the publish path");
        for (int index = 0; index < concurrency; index++) {
            equal(reached.get(artifacts.get(index).path()), index % 2 == 0 ? wanted : Disposition.ACCEPT, fixture,
                    "each concurrent publish got ITS OWN verdict; a screen keeping per-call state in a field answers "
                            + "for whichever artifact wrote the field last");
        }
    }

    private static void theChainIsAwaitedInFull(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        Sequence log = new Sequence();
        Probe slow = (Probe) log.probe("slow", -10, Disposition.ACCEPT);
        slow.pause = Duration.ofMillis(120);
        Publication publication = publication(store,
                List.of(slow, screen.create(), log.probe("behind", 10, Disposition.ACCEPT)));

        long started = System.nanoTime();
        Publication.Commit committed = commit(publication, descriptor("/kit/slow"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        isTrue(elapsed.compareTo(slow.pause) >= 0, fixture,
                "there is no timeout on the chain: a slow screen is awaited in full (" + elapsed.toMillis() + "ms for "
                        + "a " + slow.pause.toMillis() + "ms screen), which is why each screen owns its own bound");
        equal(log.assessed, List.of("slow", "behind"), fixture,
                "and there is no way to abandon the chain part-way, so the screens behind a slow one still run");
        isTrue(committed.visible(), fixture, "the publish completes once the whole chain has");
    }

    // --- clause 13: the exact position inside commit ---------------------------------------------------------------

    private static void storeThenGateLinksNoPointerFirst(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/store-then-gate");
        List<String> failures = new ArrayList<>();
        PublishInterceptor witness = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor stored, Content content) throws IOException {
                expect(failures, "the descriptor carries the content-addressed identity the store assigned",
                        stored.hash() != null && stored.size() == BODY.length());
                expect(failures, "the blob is already stored when the chain runs, so a screen sees a blob and not a "
                        + "buffer", content.store().exists("blobs/" + stored.hash()));
                expect(failures, "and no pointer of the publication's own is linked before the chain has voted",
                        content.store().readVersioned("publish" + artifact.path()).isEmpty());
                return Disposition.REJECT;
            }
        };

        Publication publication = publication(store, List.of(witness, screen.create()));
        Publication.Commit rejected = commit(publication, artifact);

        if (!failures.isEmpty()) {
            throw failure(fixture, "store-then-gate broke: " + failures);
        }
        equal(rejected.disposition(), Disposition.REJECT, fixture, "the REJECT routed the publication");
        isTrue(!rejected.visible(), fixture, "and committed no visibility");
        equal(publication.blob(artifact.path()).isPresent(), false, fixture, "a REJECT links nothing at all");
        equal(publication.blob("/quarantine" + artifact.path()).isPresent(), false, fixture,
                "not even a review pointer");
        isTrue(store.delegate().exists("blobs/" + rejected.hash()), fixture,
                "leaving an unreferenced blob for garbage collection - and so no published-then-retracted window");
    }

    private static void aQuarantinePointerPrecedesCommitted(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/quarantined");
        List<Boolean> linkedWhenNotified = new ArrayList<>();
        PublishInterceptor witness = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor stored, Content content) {
                return Disposition.QUARANTINE;
            }

            @Override
            public void committed(ArtifactDescriptor stored, Disposition disposition, ArtifactStore store)
                    throws IOException {
                linkedWhenNotified.add(store.readVersioned("publish/quarantine" + artifact.path()).isPresent());
            }
        };

        Publication publication = publication(store, List.of(witness, screen.create()));
        Publication.Commit quarantined = commit(publication, artifact);

        equal(quarantined.disposition(), Disposition.QUARANTINE, fixture, "the chain quarantined the upload");
        equal(linkedWhenNotified, List.of(true), fixture,
                "the /quarantine review pointer is written INSIDE the chain run, before committed fires - so a screen "
                        + "notified of a quarantine can already read the pointer its verdict created");
        equal(publication.located(artifact.path()).isPresent(), false, fixture, "the path itself does not serve");
        equal(publication.located("/quarantine" + artifact.path()).isPresent(), true, fixture,
                "while the review view does: stored, diverted, reviewable");
        isTrue(!quarantined.visible(), fixture, "and nothing about it is a committed publication");
    }

    private static void committedIsNotAVisibilityClaim(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor declined = descriptor("/kit/declined");
        List<Boolean> servingWhenNotified = new ArrayList<>();
        PublishInterceptor witness = new PublishInterceptor() {
            @Override
            public void committed(ArtifactDescriptor stored, Disposition disposition, ArtifactStore store)
                    throws IOException {
                servingWhenNotified.add(store.readVersioned("publish" + stored.path()).isPresent());
            }
        };

        // (a) the layout declines: the chain accepted, committed fired, and nothing ever serves.
        Publication publication = publication(store, List.of(witness, screen.create()));
        Publication.Commit outcome = publication.commit(declined, bytes(BODY), Publication.Republish.overwrite(),
                _ -> Publication.Visibility.declined());
        equal(outcome.disposition(), Disposition.ACCEPT, fixture, "the chain accepted");
        isTrue(!outcome.visible(), fixture, "and the layout declined, so nothing committed");
        equal(servingWhenNotified, List.of(false), fixture,
                "committed fires BEFORE the accepted layout and before the commit point, so its ACCEPT means 'the "
                        + "chain accepted' and not 'the artifact is visible'");
        equal(publication.located(declined.path()).isPresent(), false, fixture, "nothing serves");

        // (b) the republish policy refuses AFTER the chain accepted - the same trap by the other route.
        ArtifactDescriptor taken = descriptor("/kit/taken");
        commit(publication(store, List.of()), taken, "the-first-body");
        servingWhenNotified.clear();
        Throwable refused = thrownBy(() -> publication.commit(taken, bytes(BODY), Publication.Republish.refused(),
                accepted -> Publication.Visibility.at(taken.path())));
        isTrue(refused instanceof Publication.RepublishConflict, fixture,
                "a refused republish raises after the chain has already accepted and notified (was " + refused + ")");
        equal(servingWhenNotified, List.of(true), fixture,
                "the screen was notified of an ACCEPT for a publish that never landed - which is exactly why clause 2 "
                        + "requires committed to be an upsert the replay may repeat");
    }

    // --- clause 13's crash windows ---------------------------------------------------------------------------------

    private static void theCommittedToVisibilityCrashWindow(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/crash-committed");
        Sequence log = new Sequence();
        Publication publication = publication(store,
                List.of(log.probe("witness", 0, Disposition.ACCEPT), screen.create()));

        // The window nothing had ever armed: committed has fired, the declared visibility write has not.
        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));
        Throwable failed = thrownBy(() -> commit(publication, artifact));
        store.heal();

        isTrue(failed instanceof IOException, fixture, "the injected crash must fail the commit, or this check kills "
                + "nothing and everything below it is vacuous");
        // Re-derived from durable state, per gate 5: the chain really ran to completion and the pointer really did not
        // land. A crash point that stopped biting fails here rather than passing.
        equal(log.committed, List.of("witness"), fixture,
                "the screen was told it ACCEPTED - it believes it accepted an artifact that never became visible");
        equal(store.delegate().readVersioned("publish" + artifact.path()).isEmpty(), true, fixture,
                "while the declared visibility write never landed, so the crash is in the window this check names");
        isTrue(store.delegate().exists("blobs/" + PublicationHookContract.hash(BODY)), fixture,
                "the blob is there, unreferenced - nothing serves, nothing is observed");
        equal(publication.located(artifact.path()).isPresent(), false, fixture, "and the path does not serve");

        // The replay repairs it, and only because committed is an upsert.
        Map<String, String> afterCrash = screen.projection(store);
        Publication.Commit replayed = commit(publication(store, List.of(screen.create())), artifact);
        isTrue(replayed.visible(), fixture, "the byte-identical replay completes the publish");
        equal(publication.located(artifact.path()).isPresent(), true, fixture, "and the artifact now serves");
        Map<String, String> afterReplay = screen.projection(store);
        commit(publication(store, List.of(screen.create())), artifact);
        equal(screen.projection(store), afterReplay, fixture,
                "and the screen's record upserted across the replay rather than growing once per attempt (it held "
                        + afterCrash.size() + " row(s) after the crash and " + afterReplay.size() + " after the "
                        + "replay, and a further replay changed nothing)");
    }

    private static void theBlobToChainCrashWindow(PublicationHookFixture fixture, FaultInjectingStore store)
            throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/crash-blob");
        Sequence log = new Sequence();
        Publication publication = publication(store,
                List.of(log.probe("witness", 0, Disposition.ACCEPT), screen.create()));

        store.failNextOn(FaultInjectingStore.Op.SIZE, FaultInjectingStore.keyPrefix("blobs/"));
        Throwable failed = thrownBy(() -> commit(publication, artifact));
        store.heal();

        isTrue(failed instanceof IOException, fixture, "the injected crash must fail the commit");
        isTrue(store.delegate().exists("blobs/" + PublicationHookContract.hash(BODY)), fixture,
                "the blob landed before the crash - re-derived from the store, so a point that stopped biting fails");
        equal(log.assessed, List.of(), fixture,
                "and the chain never ran: nothing was screened, so nothing may have been recorded about it");
        equal(publication.blob(artifact.path()).isPresent(), false, fixture, "nothing was linked");

        Publication.Commit replayed = commit(publication(store, List.of(screen.create())), artifact);
        isTrue(replayed.visible(), fixture,
                "and a replay of the same bytes converges onto exactly the state a clean publish would have left - "
                        + "the blob is content-addressed, so the replay dedupes onto the orphan the crash left");
        equal(replayed.hash(), PublicationHookContract.hash(BODY), fixture, "onto the very same blob");
    }

    private static void theQuarantinePointerToCommittedCrashWindow(PublicationHookFixture fixture,
                                                                   FaultInjectingStore store) throws Exception {
        Interceptor screen = (Interceptor) fixture;
        ArtifactDescriptor artifact = descriptor("/kit/crash-quarantine");
        Sequence log = new Sequence();
        List<String> feed = new ArrayList<>();
        PublicationObserver watcher = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor published, ArtifactStore store) {
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) {
                feed.add(subject.path());
            }
        };
        Probe holder = (Probe) log.probe("holder", -5, Disposition.QUARANTINE);
        List<PublicationObserver> hooks = List.of(holder, screen.create(), watcher);

        store.crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED,
                FaultInjectingStore.keyPrefix("publish/quarantine"));
        Throwable failed = thrownBy(() -> commit(publication(store, hooks), artifact));
        store.heal();

        isTrue(failed instanceof IOException, fixture, "the injected crash must fail the commit");
        equal(store.delegate().readVersioned("publish/quarantine" + artifact.path()).isPresent(), true, fixture,
                "the review pointer DID land before the crash - which is what makes this the quarantine window rather "
                        + "than a plain failed screen");
        equal(log.committed, List.of(), fixture, "and committed had not fired, so no screen recorded the hold");
        equal(feed, List.of(), fixture,
                "and the withhold-change feed had not fired either: the marker write commits BEFORE the notify, so "
                        + "this window loses the signal");

        // The replay: the hold stands and the screens are notified this time. The feed, however, stays silent - the
        // re-link overwrites a present pointer, which is an idempotent converge and not a transition. That is the
        // documented lossy seam (`onWithheld` fires "only on an actual state transition"), and its consequence is
        // sharper than it looks: a signal lost in this window is never re-emitted by any replay, so a durable
        // consumer's periodic rebuild-from-truth is the ONLY thing that heals it.
        Sequence replay = new Sequence();
        Probe again = (Probe) replay.probe("holder", -5, Disposition.QUARANTINE);
        Publication.Commit replayed = commit(publication(store, List.of(again, screen.create(), watcher)), artifact);

        equal(replayed.disposition(), Disposition.QUARANTINE, fixture, "the replay reaches the same verdict");
        equal(replay.committed, List.of("holder"), fixture, "and the screen is notified this time");
        equal(feed, List.of(), fixture,
                "while the feed stays silent, because the second link is an overwrite rather than a transition. A "
                        + "withhold signal lost in this crash window is therefore never re-emitted, which is exactly "
                        + "why the SPI requires a durable feed consumer to keep its own rebuild-from-truth rather "
                        + "than trusting the event stream.");
        equal(publication(store, List.of()).located("/quarantine" + artifact.path()).isPresent(), true, fixture,
                "and the hold still stands - a crashed quarantine never becomes a release");
    }

    // --- the kit's own probes ---------------------------------------------------------------------------------------

    /** An interceptor overriding the inherited observer leg with a failure, to drive the contained side. */
    private static PublishInterceptor observing(Throwable failure) {
        return new PublishInterceptor() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                switch (failure) {
                    case IOException checked -> throw checked;
                    case RuntimeException unchecked -> throw unchecked;
                    case Error error -> throw error;
                    default -> throw new IllegalStateException(failure);
                }
            }
        };
    }

    /** A shared, ordered record of what the chain did - the only way to assert "asked" and "not asked" apart. */
    private static final class Sequence {

        private final List<String> assessed = new ArrayList<>();
        private final List<String> committed = new ArrayList<>();
        private final List<Disposition> outcomes = new ArrayList<>();

        private PublishInterceptor probe(String name, int order, Disposition verdict) {
            return new Probe(this, name, order, verdict);
        }
    }

    /** One kit-owned screen: a fixed verdict, a position, an optional failure per leg, and an optional pause. */
    private static final class Probe implements PublishInterceptor {

        private final Sequence sequence;
        private final String name;
        private final int order;
        private final Disposition verdict;
        private Throwable assessFailure;
        private Throwable committedFailure;
        private Duration pause;

        private Probe(Sequence sequence, String name, int order, Disposition verdict) {
            this.sequence = sequence;
            this.name = name;
            this.order = order;
            this.verdict = verdict;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
            if (pause != null) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            raise(assessFailure);
            sequence.assessed.add(name);
            return verdict;
        }

        @Override
        public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
                throws IOException {
            raise(committedFailure);
            sequence.committed.add(name);
            sequence.outcomes.add(disposition);
        }

        private static void raise(Throwable failure) throws IOException {
            switch (failure) {
                case null -> {
                }
                case IOException checked -> throw checked;
                case RuntimeException unchecked -> throw unchecked;
                case Error error -> throw error;
                default -> throw new IllegalStateException(failure);
            }
        }
    }

    /** Watches what a fixture's own screen answered, so "never null" is asserted about the real implementation
     *  rather than about a probe. */
    private static final class Verdicts {

        private final List<Disposition> verdicts = Collections.synchronizedList(new ArrayList<>());

        private PublishInterceptor watching(PublishInterceptor delegate) {
            Verdicts owner = this;
            return new PublishInterceptor() {
                @Override
                public int order() {
                    return delegate.order();
                }

                @Override
                public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                    Disposition verdict = delegate.assess(artifact, content);
                    owner.verdicts.add(verdict);
                    return verdict;
                }

                @Override
                public boolean withheld(String path, ArtifactStore store) throws IOException {
                    return delegate.withheld(path, store);
                }

                @Override
                public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
                        throws IOException {
                    delegate.committed(artifact, disposition, store);
                }
            };
        }
    }
}
