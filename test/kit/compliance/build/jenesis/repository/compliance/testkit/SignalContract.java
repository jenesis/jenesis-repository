package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.ExploitProbabilitySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * The executable {@link SignalSourceProvider} contract: one parameterized body of checks that every signal source runs
 * through a {@link SignalFixture}, so a feed property is stated once and proven per vendor instead of being
 * re-asserted - and quietly re-interpreted - in a hand-written suite per feed. Each {@link Property} names one
 * documented contract clause; {@link #checks(SignalFixture)} binds them to a fixture's recordings.
 *
 * <h2>Every property also declares what must break it</h2>
 * A check states what a feed must do; nothing in a check states that it <em>could have said otherwise</em> (,
 * carrying the earlier mechanism here). So each {@link Property} names one or more {@link Mutation}s - a {@link Mutant}
 * that removes exactly the behaviour the property is about - and the JUnit driver runs the same check body a second
 * time against each, requiring an {@link AssertionError}. All thirteen carry one, so nothing here is exempt.
 * {@link Property#READ_PATH_EGRESS}'s mutation is the sharpest of them: it is the earlier own finding turned into a
 * probe, and it goes red only while the {@link NoEgressResolver} record is complete rather than sampled.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the signal, the property and the
 * expectation, so this module stays the compliance and store SPIs plus the JDK, and any test module can
 * require it. The JUnit driver lives under {@code test/**} and turns each check into one dynamic test.
 *
 * <h2>Every check builds its own source, against its own endpoint</h2>
 * A signal source caches - in a {@code FeedCache}, in a volatile catalogue set - and a provider may memoize per
 * endpoint. A check that inherited the previous check's warm source would silently stop testing what it says it does:
 * the page-cap leg would answer from the recorded-payload leg's cache and never paginate at all. So every check takes
 * a fresh {@link RecordedFeed#endpoint() endpoint} and resolves a fresh source through
 * {@link SignalSourceProvider#named}, which is also the SPI's own idempotency clause taken at its word - resolving
 * twice must not refresh anything.
 *
 * <h2>What the read-purity check actually proves</h2>
 * {@link Property#READ_PATH_EGRESS} builds the source from its <em>production</em> configuration and asks it one
 * question with the {@link NoEgressResolver} tripwire installed. A feed declaring {@link SignalFixture.Reads#RENDERS_SNAPSHOT}
 * must reach for nothing at all; a feed declaring {@link SignalFixture.Reads#FETCHES_ON_QUERY} must be caught reaching
 * for the host it named - so the &sect;10 position of every feed is a claim this kit can falsify in either direction,
 * rather than a paragraph in a javadoc. Nothing is stubbed: the refusal happens in the JDK's own resolver, below any
 * socket the feed could have opened instead.
 *
 * <h2>The one leg that drives a vendor which is <em>working</em></h2>
 * {@link Property#CLEAN_ANSWER_IS_NOT_AN_OUTAGE} is the kit's statement of design gate 4. Every other failure leg
 * points a feed at a broken vendor and demands the declared fail mode; this one points it at a working vendor that
 * simply has nothing to report, which is the only observation that can tell whether "nothing known" and "could not
 * ask" are the same value. A feed that raises over a clean coordinate blocks every clean publish. A feed whose outage
 * reads exactly like a clean answer passes every outage - and that is the defect the whole kit is for, so there is no
 * value a fixture can declare to record it: a fail-soft feed must produce a clean answer that differs from its
 * neutral one, which it can only do by carrying {@code SignalSource.freshness()}'s authoritative half into the
 * question it asks.
 *
 * <h2>Clauses this kit discharges</h2>
 *
 * {@code SELF_SKIPS_UNCONFIGURED} is the absence sentinel and the selection-failure clause, the three answer-shape
 * checks ({@code RECORDED_ANSWER}, {@code REJECTED_STATUS}, {@code MALFORMED_BODY}) plus
 * {@code CLEAN_ANSWER_IS_NOT_AN_OUTAGE} the error-visibility clause, {@code READ_PATH_EGRESS} the read-purity clause,
 * {@code WARM_READ_DECLARED} with {@code AGED_ANSWER_TAKES_FAIL_MODE} the staleness clause, and
 * {@code CREATE_IS_A_FACTORY} the lifecycle clause. The <b>vendor field-mapping</b> clause is deliberately NOT
 * claimed: this kit drives a recorded payload and can prove the answer is well-shaped, never that the mapping is
 * faithful to the vendor's live API - which is precisely why that clause is a checkup row.
 *
 * @jenesis.covers build.jenesis.repository.compliance.SignalSourceProvider 3, 4, 6, 7, 8, 9
 */
public final class SignalContract {

    /** The five {@link SignalSource} contracts a provider's {@code signals()} may declare - the vocabulary the
     *  declaration leg holds a fixture to, so an under- or over-advertised signal is visible. */
    public static final List<Class<? extends SignalSource>> CONTRACTS = List.of(
            AdvisorySource.class, KnownExploitedSource.class, ExploitProbabilitySource.class,
            HealthSource.class, AdvisorySignal.class);

    /** The contracts keyed on a coordinate rather than on a CVE - the ones for which "no coordinate family" would be
     *  a fixture opting out of the ecosystem leg rather than a fact about the signal. */
    private static final List<Class<? extends SignalSource>> COORDINATE_KEYED =
            List.of(AdvisorySource.class, HealthSource.class);

    /** How far the aged-answer leg moves the clock for a feed that renders a durable snapshot. Such a feed declares
     *  no warm window - it holds its data until a refresh replaces it, not for an interval - so the leg simply moves
     *  past any refresh cadence in the family and asserts the render is unmoved by the age. */
    private static final Duration BEYOND_ANY_SNAPSHOT_WINDOW = Duration.ofDays(7);

    /**
     * One documented contract clause of {@link SignalSourceProvider}. The enum is the kit's vocabulary: the census
     * fails on a property no fixture exercises, so the list can never grow a clause that is asserted nowhere.
     */
    public enum Property {

        /** Every configuration the fixture declares unconfigured makes the provider yield empty, and each contract's
         *  own {@code resolve} then folds the empty candidate set into its identity-comparable neutral element -
         *  never {@code null}, never a value that reads as "clean" (clauses 3 and 4). */
        SELF_SKIPS_UNCONFIGURED,

        /** Creating a source is a factory call, not a fetch: it spends no request and touches no snapshot space
         *  (clause 2). The snapshot half is proven by the absence of a bound deployment - a provider that probed it
         *  would throw rather than pass. */
        CREATE_IS_A_FACTORY,

        /** {@code signals()} declares exactly what the created object is: every declared contract is installed and
         *  answered, and every contract the fixture did <em>not</em> declare is neither installed nor implemented, so
         *  the declaration can neither under- nor over-advertise (clause 6). */
        DECLARED_SIGNALS_MATCH,

        /** The recorded payload parses into the answer the fixture declares, that answer differs from the neutral one
         *  (so no other leg of this contract can pass vacuously), and asking twice yields the same answer. */
        RECORDED_ANSWER,

        /** A rejected status carrying the vendor's <em>own good payload</em> takes the declared fail mode: a closed
         *  feed raises, a soft one answers neutral. Because the body under the bad status really would parse, a feed
         *  that lost its status branch is caught returning data (clause 7). */
        REJECTED_STATUS,

        /** A 200 the feed cannot make sense of takes the same declared fail mode - a proxy error page and an empty
         *  catalogue are outages, never authoritative answers. */
        MALFORMED_BODY,

        /** The vendor's own "I carry nothing for this" answer is an <em>answer</em> - it never raises - and a consumer
         *  can tell it from an outage in the way the fixture declares: by the raise, by a companion the contract
         *  carries, or (declared, not excused) not at all. Gate 4 stated per signal. */
        CLEAN_ANSWER_IS_NOT_AN_OUTAGE,

        /** A recording that never stops paginating ends in a named failure carrying no partial answer, and the source
         *  is unpoisoned afterwards; a feed that declares it does not paginate is held to exactly one request per
         *  cold lookup, so the declaration is not an exit (gate 4). */
        PAGE_CAP_IS_NAMED,

        /** The declared read behaviour holds at the wire: a warm feed serves its second query without a request and a
         *  freshly created one does not inherit that warmth, while a fetching feed spends a request every time. */
        WARM_READ_DECLARED,

        /** An answer the feed has already drawn, past the window it declared for it, with the vendor no longer able
         *  to refresh it: the declared fail mode governs there too, and the reading never claims an aged answer was
         *  just fetched. Gate 4 at the one moment {@link #WARM_READ_DECLARED} cannot reach, and the leg is
         *  about. */
        AGED_ANSWER_TAKES_FAIL_MODE,

        /** A query against the production configuration reaches exactly what the fixture declared it reaches -
         *  nothing for a snapshot-rendering feed, its own vendor host for a fetching one - proven by the JDK-level
         *  no-egress tripwire rather than by an injected stub (clause 8, &sect;10). */
        READ_PATH_EGRESS,

        /** Each covered coordinate family reaches the wire in the vendor's own spelling, and an ecosystem the vendor
         *  does not cover spends no request at all - the {@code Ecosystems.Vocabulary} sentinel, proven at the wire.
         *  A CVE-keyed signal is held instead to declaring no coordinate-keyed contract. */
        ECOSYSTEM_ROUNDTRIP,

        /** The last-refresh instant is real and it moves (clause 9, &sect;10). A source that has been asked nothing
         *  reports no fetch instant at all, and one recorded fetch later it reports one - so a stamp fabricated at
         *  construction, or one that never advances, fails here rather than rendering as a plausible date beside an
         *  empty panel. */
        STALENESS_STAMP
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
     * predicate is the contract's, not the fixture's: a mutation whose removed behaviour a feed's shape genuinely
     * does not have (an endless recording for a feed with no cursor) is declared inapplicable here, in front of
     * whoever reviews the kit, rather than waived inside the fixture it would excuse.
     */
    public record Mutation(Mutant mutant, Predicate<SignalFixture> appliesTo, String why) {

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

    /**
     * What must break each property, keyed by property - the kit's falsification declaration. Read as a
     * table: <em>this</em> property is about <em>that</em> behaviour, so removing it must turn the check red. Every
     * one of the thirteen carries at least one, so the kit's {@code UNFALSIFIABLE} list is empty and stays that way.
     *
     * <p>The mutations are chosen to be the <em>weakest</em> break the property forbids rather than the most
     * destructive one available: a feed nothing resolves would fail almost every check here, and a table full of it
     * would be a table of trivial mutations satisfying a count while proving nothing. Where a property's check has
     * two independent halves - the parse and the repeat, the warm read and the fetching one - each half carries its
     * own mutation, predicated on the declaration that decides which half a given feed exercises.
     */
    public static Map<Property, List<Mutation>> mutations() {
        Map<Property, List<Mutation>> mutations = new EnumMap<>(Property.class);
        mutations.put(Property.SELF_SKIPS_UNCONFIGURED, List.of(
                new Mutation(Mutant.AN_UNCONFIGURED_SHAPE_THAT_IS_REALLY_ENABLED,
                        "the property is that a configuration the fixture calls unconfigured really reaches "
                                + "resolution and really declines it. A shape that is in fact the production one must "
                                + "therefore build a source and fail here - which is the 'switched off in a report "
                                + "but not in resolution' defect, and the only thing a decline nobody drives could "
                                + "be hiding")));
        mutations.put(Property.CREATE_IS_A_FACTORY, List.of(
                new Mutation(Mutant.A_CREATION_THAT_FETCHES,
                        "clause 2 is one sentence about one thing: resolving a source spends no request. One request "
                                + "spent during resolution is that sentence's negation and nothing more - the "
                                + "smallest form of a provider that refreshes a feed, spends a rate-limit token or "
                                + "moves a staleness stamp while merely being built")));
        mutations.put(Property.DECLARED_SIGNALS_MATCH, List.of(
                new Mutation(Mutant.A_SOURCE_THAT_IS_ALSO_SOMETHING_ELSE,
                        "the declaration must be held in BOTH directions, and over-advertising is the direction a "
                                + "check written from the fixture's side never sees: the source answers everything it "
                                + "answered before and one contract more, so only a comparison of signals() against "
                                + "the object itself can tell. Resolution filters by instanceof, so an "
                                + "over-advertised contract silently routes a coordinate through a feed that has "
                                + "nothing to say about it")));
        mutations.put(Property.RECORDED_ANSWER, List.of(
                new Mutation(Mutant.A_SOURCE_THAT_ANSWERS_NOTHING,
                        "the kit's general vacuity probe, declared here because this is the one property no feed can "
                                + "hold without doing its work: the check requires the recorded payload to produce "
                                + "the fixture's declared answer AND that answer to differ from the neutral one, so a "
                                + "source that answers the SPI's own 'no feed is active' sentinel to everything is "
                                + "exactly what it must refuse. Every other property is measured against this probe "
                                + "by the census rather than by a declaration - see the survivor legs there"),
                new Mutation(Mutant.A_VENDOR_THAT_CARRIES_NOTHING,
                        "the parse half: the recorded payload must produce the declared answer, so the mutation is "
                                + "the vendor answering its own 'nothing here' document instead. It still parses, "
                                + "still spends its request and still answers - it just answers nothing, which is "
                                + "what a recording that drifted from the vendor's wire shape produces"),
                new Mutation(Mutant.A_VENDOR_THAT_CHANGES_ITS_MIND,
                        fixture -> fixture.reads() == SignalFixture.Reads.FETCHES_ON_QUERY,
                        "and the determinism half, which is a separate claim: a gate re-screens on migration and "
                                + "re-analysis, so an answer that drifts between two identical queries changes a "
                                + "stored verdict without the artifact having changed. Declared only for a feed that "
                                + "really asks twice - a warm one answers its second query from what it holds, so "
                                + "the vendor could say anything and the leg would be measuring the cache")));
        mutations.put(Property.REJECTED_STATUS, List.of(
                new Mutation(Mutant.A_REJECTION_THE_FEED_ACCEPTS,
                        "the property exists because the rejection carries the vendor's own GOOD payload, so a feed "
                                + "that lost its status branch is caught returning data. The mutation is that branch "
                                + "made irrelevant - the same body under a 200 - and a check that survives it is one "
                                + "that never looked at the status either")));
        mutations.put(Property.MALFORMED_BODY, List.of(
                new Mutation(Mutant.A_MALFORMED_BODY_THE_FEED_PARSES,
                        "the same claim on the other broken-vendor route: a 200 that cannot be read is an outage. The "
                                + "mutation makes the body readable, so a feed that treats every 200 as data reads "
                                + "exactly like one that checks")));
        mutations.put(Property.CLEAN_ANSWER_IS_NOT_AN_OUTAGE, List.of(
                new Mutation(Mutant.A_CLEAN_ANSWER_THAT_IS_AN_OUTAGE,
                        "this is the one leg that drives a vendor which is WORKING, and the mutation is exactly that "
                                + "premise removed: the 'nothing here' recording is replaced by the outage one. Gate "
                                + "4 is the claim that a consumer can tell those two apart, so a check that cannot "
                                + "tell them apart itself is the defect the whole kit is for")));
        mutations.put(Property.PAGE_CAP_IS_NAMED, List.of(
                new Mutation(Mutant.AN_ENDLESS_FEED_THAT_ENDS,
                        fixture -> fixture.paging() == SignalFixture.Paging.CURSOR_CAPPED,
                        "the cursor half: the cap is proven by a recording that never stops paginating, so a "
                                + "recording that stops lets a feed with no cap at all answer normally. A cap that "
                                + "returns a plausible-but-incomplete answer is worse than no cap, and this is what "
                                + "keeps the difference observable"),
                new Mutation(Mutant.A_LOOKUP_THAT_SPENDS_AN_EXTRA_REQUEST,
                        fixture -> fixture.paging() != SignalFixture.Paging.CURSOR_CAPPED,
                        "the no-cursor half: a feed declaring one document, or a chain of exactly N forward steps, is "
                                + "held to that count so the declaration cannot become a way of declining the leg. "
                                + "One extra request is the smallest break of it - a step that appeared, a retry that "
                                + "was added - and it is invisible to any assertion that only read the answer")));
        mutations.put(Property.WARM_READ_DECLARED, List.of(
                new Mutation(Mutant.A_QUERY_THAT_ANSWERS_FROM_MEMORY,
                        fixture -> fixture.reads() == SignalFixture.Reads.FETCHES_ON_QUERY,
                        "the fetching half: 'every query fetches' is proven by counting the requests a second query "
                                + "spends, so a feed that quietly grew a cache must fail here. That growth is the "
                                + "defect - a cold gate decision that silently stopped depending on the vendor is a "
                                + "different §10 position from the one declared"),
                new Mutation(Mutant.A_LOOKUP_THAT_SPENDS_AN_EXTRA_REQUEST,
                        fixture -> fixture.reads() != SignalFixture.Reads.FETCHES_ON_QUERY,
                        "and the warm half, mirror image: a warm read must spend NOTHING, so one request on the "
                                + "second query is the smallest break of it. The vendor meters this API, so a serve "
                                + "that pays for a lookup it already holds is quota spent on nothing")));
        mutations.put(Property.AGED_ANSWER_TAKES_FAIL_MODE, List.of(
                new Mutation(Mutant.A_QUERY_THAT_ANSWERS_FROM_MEMORY,
                        fixture -> fixture.reads() == SignalFixture.Reads.WARM_THEN_RENDERS
                                || fixture.reads() == SignalFixture.Reads.SNAPSHOT_WITH_LAZY_REFRESH,
                        "the leg's whole premise is that the feed's own window LAPSES and it asks the vendor again. A "
                                + "feed that answers from what it holds for ever never reaches the aged case at all, "
                                + "and the check would then be comparing two answers drawn from the same fetch - "
                                + "which is the 'keeps serving what it drew without ever re-asking' defect is "
                                + "about, stated from the other side"),
                new Mutation(Mutant.A_REJECTION_THE_FEED_ACCEPTS,
                        fixture -> fixture.reads() == SignalFixture.Reads.FETCHES_ON_QUERY,
                        "for a feed that holds nothing, the aged case IS the next query over a vendor that stopped "
                                + "answering, so the mutation is that vendor answering after all. A fail-closed feed "
                                + "must then be caught not raising and a fail-soft one not answering its neutral"),
                new Mutation(Mutant.A_LOOKUP_THAT_SPENDS_AN_EXTRA_REQUEST,
                        fixture -> fixture.reads() == SignalFixture.Reads.RENDERS_SNAPSHOT,
                        "for a mirror there is no window to lapse: its read path renders whatever it committed, "
                                + "however old, which is what makes a gate decision stand while the vendor is down. "
                                + "What that leaves to break is the render itself fetching - one request on the read "
                                + "path is the smallest form of a mirror that quietly re-checks, and it is precisely "
                                + "the moment a gate decision stops standing"),
                new Mutation(Mutant.A_STAMP_THAT_MOVES_WHEN_IT_SERVES,
                        fixture -> fixture.failMode() == SignalFixture.FailMode.SOFT
                                && fixture.reads() != SignalFixture.Reads.FETCHES_ON_QUERY,
                        "and the half a raise does not cover: a fail-soft feed keeps answering what it drew, and the "
                                + "consumer's only protection is reading how old that is. A stamp that moves to the "
                                + "moment the answer was SERVED renders beside a panel exactly as a fresh one does, "
                                + "and nothing but this comparison would ever notice")));
        mutations.put(Property.READ_PATH_EGRESS, List.of(
                new Mutation(Mutant.A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED,
                        "the property is that a production query reaches EXACTLY what the fixture declared, so the "
                                + "mutation is one host more. It is deliberately a host this JVM resolved a moment "
                                + "earlier, because that is where this leg's instrument can lie: found the "
                                + "tripwire recording only the first refusal per host, so a query reaching a vendor a "
                                + "sibling fixture had already reached read as a query that reached nothing. With "
                                + "the failed-lookup cache off the repeat is recorded and this leg goes red; with it "
                                + "on the mutation is invisible, and the mutant says so by breaking the harness "
                                + "rather than passing")));
        mutations.put(Property.ECOSYSTEM_ROUNDTRIP, List.of(
                new Mutation(Mutant.AN_ECOSYSTEM_ALWAYS_QUERIED_THE_SAME_WAY,
                        fixture -> fixture.families().size() > 1,
                        "the property is that each covered family reaches the wire in the VENDOR's spelling of that "
                                + "family, so the mutation is one spelling used for all of them. A map that answers "
                                + "the same name whatever it is asked is exactly what a vocabulary keyed on another "
                                + "vendor's names degrades into - GitHub calls PyPI 'pip', deps.dev calls it 'pypi' - "
                                + "and the ecosystem it stops matching is one that is quietly never screened. It "
                                + "needs two families to be observable at all, which is what a CVE-keyed signal does "
                                + "not have")));
        mutations.put(Property.STALENESS_STAMP, List.of(
                new Mutation(Mutant.A_STAMP_FABRICATED_AT_CONSTRUCTION,
                        "the property is not that an instant exists - freshness() is abstract, so it always does - "
                                + "but that it is EARNED. The mutation is the instant that is not: a source that has "
                                + "fetched nothing reporting one anyway, which renders beside an empty panel exactly "
                                + "as a healthy feed does and is the whole reason this leg drives the cold source "
                                + "first")));
        return Collections.unmodifiableMap(mutations);
    }

    /** The mutations {@code fixture}'s leg of {@code property} must fail against - every declared one whose shape
     *  this feed has. */
    public static List<Mutation> mutations(SignalFixture fixture, Property property) {
        Objects.requireNonNull(fixture, "fixture");
        return mutations().getOrDefault(property, List.of()).stream()
                .filter(mutation -> mutation.appliesTo().test(fixture))
                .toList();
    }

    /** The body of a {@link Check}, run against a started fixture. */
    @FunctionalInterface
    public interface Body {
        void run(SignalFixture fixture) throws Exception;
    }

    private SignalContract() {
        throw new UnsupportedOperationException("SignalContract is a static utility");
    }

    /**
     * Every contract check, in declaration order. The list <em>is</em> the contract: a signal source runs all of it.
     * There is deliberately no per-fixture exclusion seam - a feed has no environment that could fail to express a
     * property, because every answer it gives here is one the fixture recorded, so an exclusion could only ever mean
     * "this one does not comply". Where feeds genuinely differ - the fail mode, the read behaviour, whether there is
     * a cursor at all - they differ by <em>declaration</em>, and the kit asserts the declared side.
     */
    public static List<Check> checks() {
        return List.of(
                // The two checks that must NOT run with a snapshot space bound: both are about creation declining or
                // being inert, and an unbound space is what makes "it did not touch its snapshots" provable - a
                // provider that read, wrote or probed one would throw rather than pass quietly.
                new Check(Property.SELF_SKIPS_UNCONFIGURED,
                        "an unconfigured feed declines and its contract folds to the neutral element",
                        SignalContract::selfSkipsUnconfigured),
                new Check(Property.CREATE_IS_A_FACTORY,
                        "creating the source spends no request and touches no snapshot space",
                        SignalContract::createIsAFactory),
                new Check(Property.DECLARED_SIGNALS_MATCH,
                        "signals() declares exactly the contracts the created source answers",
                        fixture -> bound(SignalContract::declaredSignalsMatch, fixture)),
                new Check(Property.RECORDED_ANSWER,
                        "the recorded payload parses into the declared answer, twice, and it is not the neutral one",
                        fixture -> bound(SignalContract::recordedAnswer, fixture)),
                new Check(Property.REJECTED_STATUS,
                        "a rejected status carrying the good payload takes the declared fail mode",
                        fixture -> bound(SignalContract::rejectedStatus, fixture)),
                new Check(Property.MALFORMED_BODY,
                        "a 200 the feed cannot read takes the declared fail mode",
                        fixture -> bound(SignalContract::malformedBody, fixture)),
                new Check(Property.CLEAN_ANSWER_IS_NOT_AN_OUTAGE,
                        "the vendor's own \"nothing here\" answers, and is told apart from an outage as declared",
                        fixture -> bound(SignalContract::cleanAnswerIsNotAnOutage, fixture)),
                new Check(Property.PAGE_CAP_IS_NAMED,
                        "an endless recording ends in a named cap with no partial answer",
                        fixture -> bound(SignalContract::pageCapIsNamed, fixture)),
                new Check(Property.WARM_READ_DECLARED,
                        "the declared read behaviour holds at the wire, and only a stored answer survives a restart",
                        fixture -> bound(SignalContract::warmReadDeclared, fixture)),
                new Check(Property.AGED_ANSWER_TAKES_FAIL_MODE,
                        "an answer past its own window, with the vendor unable to refresh it, takes the declared "
                                + "fail mode and never reads as freshly fetched",
                        SignalContract::agedAnswerTakesFailMode),
                new Check(Property.READ_PATH_EGRESS,
                        "a production query reaches exactly what the fixture declared it reaches",
                        fixture -> bound(SignalContract::readPathEgress, fixture)),
                new Check(Property.ECOSYSTEM_ROUNDTRIP,
                        "each covered ecosystem reaches the wire in the vendor's spelling; an uncovered one is not queried",
                        fixture -> bound(SignalContract::ecosystemRoundTrip, fixture)),
                new Check(Property.STALENESS_STAMP,
                        "a cold source reports no fetch instant and one recorded fetch gives it one",
                        fixture -> bound(SignalContract::stalenessStamp, fixture)));
    }

    /** Every check bound to one fixture - what a JUnit driver turns into dynamic tests. */
    public static List<Check> checks(SignalFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return checks();
    }

    /**
     * Run {@code body} with a fresh, empty deployment snapshot space bound - the space a mirroring feed commits its
     * catalogue and its staleness stamp into. Fresh per check rather than per suite, because the space is keyed on
     * the signal's name and not on its endpoint: a snapshot one check committed would otherwise be rendered by the
     * next check's source whatever recorded endpoint it was pointed at, and the page-cap and read-purity legs would
     * quietly stop fetching at all.
     *
     * <p>The binding is a process-global reference, so this assumes the suite is not run with
     * {@code -Djenesis.test.parallel}: two checks binding at once would share a space. That is the same assumption
     * the egress bracket makes, and for the same reason.
     */
    private static void bound(Body body, SignalFixture fixture) throws Exception {
        bound(body, fixture, Clock.systemUTC());
    }

    /** The same binding over a clock the check controls - {@code SignalContext.clock()} is the seam a feed takes its
     *  windows from, so a check crosses one without sleeping through it. */
    private static void bound(Body body, SignalFixture fixture, Clock clock) throws Exception {
        try (SignalContext.Deployment _ = SignalContext.deployment(SnapshotSpace.root(), clock)) {
            body.run(fixture);
        }
    }

    /**
     * A clock the kit moves by hand. The deployment binds it, every feed takes its refresh window from it, and a
     * check therefore reaches "this answer is now older than the feed says it may be" in one statement instead of in
     * six hours - which is what {@link SignalContext#clock()} exists for and why a feed is handed one rather than
     * calling {@code System.currentTimeMillis()} for itself.
     */
    private static final class Ticking extends Clock {

        private volatile Instant instant = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }
    }

    // --- the checks ------------------------------------------------------------------------------------------
    /**
     * Ask the fixture's question, having first driven the explicit refresh a snapshot-rendering feed needs.
     *
     * <p>A feed declaring {@link SignalFixture.Reads#RENDERS_SNAPSHOT} answers from durable state and fetches
     * nothing, so without a draw every leg of this contract would observe an empty source and pass vacuously. The
     * draw is the {@link RefreshableSource} role's own entry point - the same one the scheduled refresh pass calls -
     * so the kit exercises the production path rather than a back door, and the separation between "the read path"
     * and "the write path" is real in the test as well as in the javadoc.
     *
     * <p>{@link Property#READ_PATH_EGRESS} deliberately does <em>not</em> use this: that leg exists to prove the read
     * path reaches nothing, and priming it would be the one thing that fetches.
     */
    private static Object ask(SignalFixture fixture, SignalSource source) throws IOException {
        draw(fixture, source);
        return fixture.ask(source);
    }

    /** The draw on its own, for a leg that must count requests around the query rather than around the refresh. */
    private static void draw(SignalFixture fixture, SignalSource source) throws IOException {
        if (fixture.reads() != SignalFixture.Reads.RENDERS_SNAPSHOT) {
            return;
        }
        if (!(source instanceof RefreshableSource refreshable)) {
            throw failure(fixture, "declares that its query renders stored state, but the created source carries no "
                    + "RefreshableSource role - so nothing can ever put anything in the store it claims to render, "
                    + "and every answer it gives is the empty one");
        }
        refreshable.refresh();
    }


    private static void selfSkipsUnconfigured(SignalFixture fixture) {
        if (fixture.unconfigured().isEmpty()) {
            throw failure(fixture, "declares no unconfigured shape at all; every feed has at least a switched-off "
                    + "one, and a self-skip nobody drives is a self-skip nobody has seen work");
        }
        for (SignalFixture.Unconfigured shape : fixture.unconfigured()) {
            if (shape.why().isBlank()) {
                throw failure(fixture, "declares an unconfigured shape with no reason; the reason is what the failure "
                        + "prints when the decline does not happen");
            }
            for (Class<? extends SignalSource> contract : fixture.signals()) {
                SignalSource created = SignalSourceProvider.named(contract, shape.config()).get(fixture.signal());
                if (created != null) {
                    throw failure(fixture, "built a " + contract.getSimpleName() + " although " + shape.why()
                            + ". A provider that cannot answer must decline with an empty Optional, so the gate keeps "
                            + "deciding on the feeds that can rather than failing every publish against a "
                            + "half-configured vendor");
                }
                Object neutral = neutral(contract, shape.config());
                if (!isNeutral(contract, neutral)) {
                    throw failure(fixture, contract.getSimpleName() + ".resolve did not fold to its neutral element "
                            + "although " + shape.why() + " - it answered " + neutral + ". An empty candidate set must "
                            + "become the identity-comparable neutral element, never null and never a value that "
                            + "reads as clean data");
                }
            }
        }
    }

    private static void createIsAFactory(SignalFixture fixture) throws Exception {
        // The tripwire this check depends on, proven installed before it is relied on: with no deployment bound,
        // asking for the snapshot space throws. Without that, "the provider did not touch its snapshots" would be
        // vacuously true the moment a neighbouring check leaked its binding.
        boolean refused = false;
        try {
            SignalContext.of(fixture.signal(), _ -> null).snapshots();
        } catch (IllegalStateException _) {
            refused = true;
        }
        if (!refused) {
            throw failure(fixture, "ran with a snapshot space still bound, so nothing here could observe a provider "
                    + "touching one. This check must run unbound - a neighbouring check leaked its deployment");
        }
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            // Nothing is bound, so SignalContext.snapshots() throws by design. A provider that read, wrote or probed
            // its snapshot space while merely building a client surfaces here as that IllegalStateException rather
            // than as a silent extra round trip years later.
            SignalSource created = fixture.source(fixture.enabled(feed.endpoint()));
            if (created == null) {
                throw failure(fixture, "created no source under its enabled configuration");
            }
            if (!feed.requests().isEmpty()) {
                throw failure(fixture, "fetched " + feed.requests().size() + " time(s) while merely being created. "
                        + "Resolving a signal source must not refresh a feed, spend a rate-limit token or move a "
                        + "staleness stamp - refreshing is an explicit write-role action");
            }
            // Resolving twice must be equally free of effect.
            fixture.source(fixture.enabled(feed.endpoint()));
            if (!feed.requests().isEmpty()) {
                throw failure(fixture, "fetched while being resolved a second time; creation is not idempotent");
            }
        }
    }

    private static void declaredSignalsMatch(SignalFixture fixture) throws Exception {
        if (fixture.signals().isEmpty()) {
            throw failure(fixture, "declares no signal contract at all");
        }
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            SignalSource created = fixture.source(fixture.enabled(feed.endpoint()));
            for (Class<? extends SignalSource> contract : CONTRACTS) {
                boolean declared = fixture.signals().contains(contract);
                boolean installed = SignalSourceProvider.installed(contract).contains(fixture.signal());
                boolean answered = contract.isInstance(created);
                if (declared != installed) {
                    throw failure(fixture, "is " + (installed ? "" : "not ") + "installed for "
                            + contract.getSimpleName() + " but the fixture declares the opposite. installed() is the "
                            + "capability signal a console gates its surface on without creating the source, so a "
                            + "drift here shows an operator a panel the feed cannot fill (or hides one it can)");
                }
                if (declared != answered) {
                    throw failure(fixture, "created an object that is " + (answered ? "" : "not ")
                            + "a " + contract.getSimpleName() + " while signals() says the opposite. Resolution "
                            + "filters by instanceof, so a drifting declaration silently under- or over-advertises");
                }
            }
        }
    }

    private static void recordedAnswer(SignalFixture fixture) throws Exception {
        if (Objects.equals(fixture.answer(), fixture.neutral())) {
            throw failure(fixture, "declares an answer equal to its neutral value (" + fixture.neutral() + "), so "
                    + "every fail-mode leg of this contract would pass over a source that never answers anything");
        }
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            Object first = ask(fixture, source);
            if (!Objects.equals(first, fixture.answer())) {
                throw failure(fixture, "read " + first + " off its own recorded payload, expected "
                        + fixture.answer() + ". Either the recording drifted from the vendor's wire shape or the "
                        + "parser did");
            }
            Object second = fixture.ask(source);
            if (!Objects.equals(second, first)) {
                throw failure(fixture, "answered " + second + " the second time and " + first + " the first, over "
                        + "the same recording. A gate re-screens on migration and re-analysis, so a drifting answer "
                        + "would change a stored verdict without the artifact having changed");
            }
            if (feed.requests().isEmpty()) {
                throw failure(fixture, "answered without asking the recorded endpoint anything - the recording is "
                        + "not what it answered from, so this fixture proves nothing about the vendor's wire shape");
            }
        }
    }

    private static void rejectedStatus(SignalFixture fixture) throws Exception {
        assertOutage(fixture, fixture.rejected(), "a rejected status over the vendor's own good payload");
    }

    private static void malformedBody(SignalFixture fixture) throws Exception {
        assertOutage(fixture, fixture.malformed(), "a 200 the feed cannot make sense of");
    }

    private static void assertOutage(SignalFixture fixture, RecordedFeed.Responder script, String situation)
            throws Exception {
        try (RecordedFeed feed = RecordedFeed.serving(script)) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            Object answer = null;
            RuntimeException raised = null;
            try {
                answer = ask(fixture, source);
            } catch (RuntimeException e) {
                raised = e;
            }
            if (feed.requests().isEmpty()) {
                throw failure(fixture, "never asked the endpoint anything under " + situation
                        + ", so this leg tested nothing");
            }
            switch (fixture.failMode()) {
                case CLOSED -> {
                    if (raised == null) {
                        throw failure(fixture, "answered " + answer + " under " + situation + " instead of raising. "
                                + "A fail-closed feed must never let an outage read as an empty - and therefore "
                                + "clean - answer; a vulnerability nobody reported is indistinguishable from none");
                    }
                }
                case SOFT -> {
                    if (raised != null) {
                        throw failure(fixture, "raised " + raised + " under " + situation
                                + " although it declares a fail-soft mode; a ranking aid may not block a publish");
                    }
                    if (!Objects.equals(answer, fixture.neutral())) {
                        throw failure(fixture, "answered " + answer + " under " + situation + ", expected the neutral "
                                + fixture.neutral() + ". The recorded body under that status really does parse, so "
                                + "this is the feed reading an error document as data");
                    }
                }
            }
        }
    }

    /**
     * Gate 4, per signal. Every other failure leg of this contract drives the feed at a vendor that is broken; this
     * one drives it at a vendor that is <em>working and has nothing to report</em>, which is the only way to find out
     * whether the two are distinguishable. A feed that raises over a clean coordinate blocks every clean publish; one
     * whose outage is indistinguishable from a clean answer passes every outage - and the second is the failure this
     * kit exists to catch, so where it is real it is declared and asserted rather than left to be discovered by a
     * consumer acting on it.
     */
    private static void cleanAnswerIsNotAnOutage(SignalFixture fixture) throws Exception {
        boolean identical = Objects.equals(fixture.cleanAnswer(), fixture.neutral());
        switch (fixture.degradation()) {
            case RAISED -> {
                if (fixture.failMode() != SignalFixture.FailMode.CLOSED) {
                    throw failure(fixture, "declares that an outage raises, but its fail mode is "
                            + fixture.failMode() + ". Only a fail-closed feed raises, and for a fail-soft one the "
                            + "raise cannot be what separates an outage from a clean answer");
                }
                if (!identical) {
                    throw failure(fixture, "declares that an outage raises, so nothing but the vendor can produce "
                            + "its neutral answer - yet it declares the clean answer " + fixture.cleanAnswer()
                            + " against a neutral " + fixture.neutral() + ". For such a feed they are the same "
                            + "value by construction: the empty list a screened-and-found-nothing lookup yields");
                }
            }
            case FLAGGED -> {
                if (fixture.failMode() != SignalFixture.FailMode.SOFT) {
                    throw failure(fixture, "declares that an outage degrades to a flagged neutral answer, but its "
                            + "fail mode is " + fixture.failMode() + " - a fail-closed feed raises instead");
                }
                if (identical) {
                    throw failure(fixture, "declares a companion separating \"the vendor carries nothing\" from "
                            + "\"the vendor could not be reached\", but both read " + fixture.neutral() + ". The "
                            + "question this fixture asks must carry that companion - SignalSource.freshness()'s "
                            + "authoritative half - or the separation lives in the implementation and not in the "
                            + "answer a consumer acts on. There is deliberately no value to declare instead: a "
                            + "fail-soft signal whose outage reads as a clean answer is the defect this kit exists "
                            + "for, so it fails here rather than being recorded");
                }
            }
        }
        try (RecordedFeed feed = RecordedFeed.serving(fixture.clean())) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            Object answered;
            try {
                answered = ask(fixture, source);
            } catch (RuntimeException e) {
                throw failure(fixture, "raised " + e + " over the vendor's own \"nothing here\" answer. A coordinate "
                        + "the vendor screened and carries nothing for is an ANSWER; only a failure to reach the "
                        + "vendor is a failure, and a feed that cannot tell them apart blocks every clean publish");
            }
            if (feed.requests().isEmpty()) {
                throw failure(fixture, "spent no request over its clean recording, so this leg observed nothing at "
                        + "all - the answer came from somewhere other than the vendor");
            }
            if (!Objects.equals(answered, fixture.cleanAnswer())) {
                throw failure(fixture, "answered " + answered + " over the vendor's own \"nothing here\" recording, "
                        + "expected " + fixture.cleanAnswer() + ". Either the recording is not the shape this vendor "
                        + "really answers with, or the parser reads an absence as something else");
            }
        }
    }

    private static void pageCapIsNamed(SignalFixture fixture) throws Exception {
        switch (fixture.paging()) {
            case SINGLE_DOCUMENT -> {
                try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
                    SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
                    ask(fixture, source);
                    if (feed.requests().size() != 1) {
                        throw failure(fixture, "declares that it answers one document per lookup, but a cold lookup "
                                + "spent " + feed.requests().size() + " requests: " + targets(feed.requests())
                                + ". A feed that really paginates must declare " + SignalFixture.Paging.CURSOR_CAPPED
                                + " and prove its cap binds; one that resolves through a fixed chain of separate "
                                + "queries must declare " + SignalFixture.Paging.CHAINED + " and its length");
                    }
                }
            }
            case CHAINED -> {
                int steps = fixture.chainedFetches();
                if (steps < 2) {
                    throw failure(fixture, "declares a chained resolution of " + steps + " fetch(es); a single fetch "
                            + "is " + SignalFixture.Paging.SINGLE_DOCUMENT + ", and this value must not become a way "
                            + "of declining the leg");
                }
                try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
                    SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
                    ask(fixture, source);
                    List<RecordedFeed.Request> spent = feed.requests();
                    if (spent.size() != steps) {
                        throw failure(fixture, "declares a " + steps + "-step chained resolution, but a cold lookup "
                                + "spent " + spent.size() + " request(s): " + targets(spent) + ". A chain is N "
                                + "queries rather than N pages of one, so its length is the bound - a step that "
                                + "appeared or vanished changes what one gate decision costs the vendor");
                    }
                    Set<String> distinct = new LinkedHashSet<>();
                    for (RecordedFeed.Request request : spent) {
                        distinct.add(request.target());
                    }
                    if (distinct.size() != spent.size()) {
                        throw failure(fixture, "spent " + spent.size() + " requests resolving one coordinate but "
                                + "addressed only " + distinct.size() + " distinct target(s): " + targets(spent)
                                + ". A chain resolves FORWARD - each step asks what the previous answer named - so a "
                                + "repeated target is a retry loop, and no cap in this contract binds on one");
                    }
                }
            }
            case CURSOR_CAPPED -> {
                try (RecordedFeed feed = RecordedFeed.serving(fixture.endless())) {
                    SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
                    Object answer = null;
                    RuntimeException raised = null;
                    try {
                        answer = fixture.ask(source);
                    } catch (RuntimeException e) {
                        raised = e;
                    }
                    if (raised == null) {
                        throw failure(fixture, "answered " + answer + " over a recording that never stops "
                                + "paginating. A cap that returns a plausible-but-incomplete answer is worse than no "
                                + "cap: the gate cannot tell the truncation from a clean package");
                    }
                    if (!chain(raised).contains(fixture.pageCapMarker())) {
                        throw failure(fixture, "failed at its page cap with a message that does not carry \""
                                + fixture.pageCapMarker() + "\": " + chain(raised) + ". A bound must fail by name, or "
                                + "an operator cannot tell a capped fetch from an outage");
                    }
                    if (feed.requests().size() < 2) {
                        throw failure(fixture, "declares a cursor but spent " + feed.requests().size()
                                + " request(s) before failing, so nothing was actually paginated");
                    }
                    // Nothing partial may have survived: with the good recording restored the same source must
                    // answer exactly what a source that never saw the endless feed answers.
                    feed.answer(fixture.recorded());
                    Object recovered = fixture.ask(source);
                    if (!Objects.equals(recovered, fixture.answer())) {
                        throw failure(fixture, "answered " + recovered + " after a capped fetch, expected "
                                + fixture.answer() + ". The failed fetch left residue behind - a partially drawn "
                                + "answer must be dropped whole, never folded into what is served next");
                    }
                }
            }
        }
    }

    private static void warmReadDeclared(SignalFixture fixture) throws Exception {
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            // The draw happens BEFORE the counting starts: for a snapshot-rendering feed this leg is about what its
            // queries cost, and the explicit refresh is not one of them - that separation is the whole claim.
            draw(fixture, source);
            List<RecordedFeed.Request> beforeFirst = feed.requests();
            fixture.ask(source);
            int spentByFirst = feed.since(beforeFirst).size();
            List<RecordedFeed.Request> beforeSecond = feed.requests();
            Object second = fixture.ask(source);
            int spentBySecond = feed.since(beforeSecond).size();

            switch (fixture.reads()) {
                case RENDERS_SNAPSHOT -> {
                    if (spentByFirst != 0 || spentBySecond != 0) {
                        throw failure(fixture, "declares that a query renders stored state, but spent "
                                + (spentByFirst + spentBySecond) + " request(s) answering two queries");
                    }
                    if (!Objects.equals(second, fixture.answer())) {
                        throw failure(fixture, "rendered " + second + " rather than " + fixture.answer()
                                + " after its own drawn snapshot; a render that answers something else is not "
                                + "rendering what was committed");
                    }
                    // Stored, not remembered - and, unlike the lazy-refresh leg below, proven with the vendor turned
                    // hostile AND no refresh driven: a source created afresh over the same space must answer from the
                    // committed snapshot alone, spending nothing, because its read path is not allowed to fetch at all.
                    feed.answer(fixture.rejected());
                    SignalSource restarted = fixture.source(fixture.enabled(feed.endpoint()));
                    List<RecordedFeed.Request> beforeRestart = feed.requests();
                    Object afterRestart = fixture.ask(restarted);
                    if (!feed.since(beforeRestart).isEmpty()) {
                        throw failure(fixture, "spent " + feed.since(beforeRestart).size() + " request(s) answering a "
                                + "query on a freshly created source. A read path that renders must render even when "
                                + "nothing has refreshed in this process - that is what makes a gate decision stand "
                                + "while the vendor is down");
                    }
                    if (!Objects.equals(afterRestart, fixture.answer())) {
                        throw failure(fixture, "answered " + afterRestart + " after a fresh creation over the same "
                                + "snapshot space, expected " + fixture.answer() + ". Either nothing was committed or "
                                + "the render path is not being taken, and a mirror that has to re-fetch after a "
                                + "restart is a cache");
                    }
                }
                case WARM_THEN_RENDERS, SNAPSHOT_WITH_LAZY_REFRESH -> {
                    if (spentByFirst == 0) {
                        throw failure(fixture, "spent no request on its first, cold query; a feed that refreshes "
                                + "lazily must fetch once before it can render");
                    }
                    if (spentBySecond != 0) {
                        throw failure(fixture, "spent " + spentBySecond + " request(s) on a warm second query; the "
                                + "vendor meters this API, so a serve must not pay for (or spend quota on) a lookup "
                                + "it already holds");
                    }
                    // The prior-good answer stands even when the vendor turns hostile.
                    feed.answer(fixture.rejected());
                    Object warm = fixture.ask(source);
                    if (!Objects.equals(warm, fixture.answer())) {
                        throw failure(fixture, "stopped serving its prior-good answer once the vendor started "
                                + "rejecting; it answered " + warm + " instead of " + fixture.answer());
                    }
                    // The one observation that tells a STORED answer from a REMEMBERED one: create the source afresh
                    // - a restart, in every respect that matters - over the same snapshot space and the same
                    // rejecting vendor. A durable snapshot answers; a process-local cache cannot.
                    SignalSource restarted = fixture.source(fixture.enabled(feed.endpoint()));
                    List<RecordedFeed.Request> beforeRestart = feed.requests();
                    boolean survived;
                    try {
                        survived = Objects.equals(fixture.ask(restarted), fixture.answer());
                    } catch (RuntimeException _) {
                        survived = false;
                    }
                    int spentByRestart = feed.since(beforeRestart).size();
                    if (fixture.reads() == SignalFixture.Reads.WARM_THEN_RENDERS && survived) {
                        throw failure(fixture, "answered from durable state after a fresh creation over a rejecting "
                                + "vendor. That is stronger than " + SignalFixture.Reads.WARM_THEN_RENDERS
                                + " - declare " + SignalFixture.Reads.SNAPSHOT_WITH_LAZY_REFRESH
                                + " and let the kit assert it");
                    }
                    if (fixture.reads() == SignalFixture.Reads.SNAPSHOT_WITH_LAZY_REFRESH && !survived) {
                        throw failure(fixture, "lost its answer when the source was created afresh, although it "
                                + "declares a durable snapshot. Either nothing was committed or the render path is "
                                + "not being taken - a mirror that has to re-fetch after a restart is a cache, so "
                                + "declare " + SignalFixture.Reads.WARM_THEN_RENDERS + " instead");
                    }
                    if (fixture.reads() == SignalFixture.Reads.SNAPSHOT_WITH_LAZY_REFRESH && spentByRestart != 0) {
                        throw failure(fixture, "spent " + spentByRestart + " request(s) rendering a snapshot that was "
                                + "still inside its refresh window; a committed snapshot is rendered, never "
                                + "re-fetched, or a second replica costs the vendor a call per boot");
                    }
                }
                case FETCHES_ON_QUERY -> {
                    if (spentByFirst == 0 || spentBySecond == 0) {
                        throw failure(fixture, "declares that every query fetches, but a query spent no request "
                                + "(first: " + spentByFirst + ", second: " + spentBySecond + "). A feed that holds a "
                                + "warm answer must declare " + SignalFixture.Reads.WARM_THEN_RENDERS);
                    }
                    if (!Objects.equals(second, fixture.answer())) {
                        throw failure(fixture, "answered " + second + " on its second fetch of the same recording");
                    }
                }
            }
        }
    }

    /**
     * <strong>, made executable.</strong> Every other failure leg of this contract drives a feed that has
     * nothing cached; {@link Property#WARM_READ_DECLARED} drives one whose answer is still inside its window. This
     * leg drives the case in between, which is the one no leg could reach before and the one an outage actually
     * produces: an answer the feed <em>already drew</em>, now older than the feed's own declared window, over a
     * vendor that can no longer refresh it.
     *
     * <p>The contract's two halves meet here, and they disagree on purpose:
     * <ul>
     * <li>a {@link SignalFixture.FailMode#CLOSED} feed must <b>raise</b>. Its answer is read for its emptiness, so an
     *     aged one is a screen that did not happen - a gate passing on it cannot tell "nothing was reported" from
     *     "nobody looked since yesterday", which is precisely what clause 4 exists to prevent. Serving it is what
     *     five licensed feeds did indefinitely while a vendor was down;</li>
     * <li>a {@link SignalFixture.FailMode#SOFT} feed must <b>keep answering</b> what it drew - the aged value is real
     *     evidence and its neutral is none - and must <b>not move its fetch instant</b>, because the consumer's whole
     *     protection is reading how old the answer is.</li>
     * </ul>
     *
     * <p>Reaching that state needs the feed's own window crossed, which is why the fixture declares it and why the
     * deployment clock is bound to something this check can move. The declaration is held to on both sides - half the
     * window in, the feed must still answer without a request - so a window declared generously enough to make this
     * leg easy fails the other half.
     */
    private static void agedAnswerTakesFailMode(SignalFixture fixture) throws Exception {
        Ticking clock = new Ticking();
        bound(bound -> agedAnswerTakesFailMode(bound, clock), fixture, clock);
    }

    private static void agedAnswerTakesFailMode(SignalFixture fixture, Ticking clock) throws Exception {
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            Object fresh = ask(fixture, source);
            if (!Objects.equals(fresh, fixture.answer())) {
                throw failure(fixture, "answered " + fresh + " over its own good recording, expected "
                        + fixture.answer() + ". This leg needs a real answer to age before it can ask what an aged "
                        + "one is worth");
            }
            Instant drawn = source.freshness().refreshed().orElseThrow(() -> failure(fixture,
                    "reported no fetch instant after a successful recorded query, so nothing here could tell an aged "
                            + "answer from a freshly drawn one"));
            switch (fixture.reads()) {
                case WARM_THEN_RENDERS, SNAPSHOT_WITH_LAZY_REFRESH ->
                        agedWarmAnswer(fixture, clock, feed, source, drawn);
                case FETCHES_ON_QUERY -> {
                    // There is no answer to age: every query is a fetch, so the very next one over a broken vendor
                    // must already take the declared fail mode. Asserted from a WARM source rather than a cold one,
                    // which is what makes it more than a repeat of the outage leg - a feed that quietly grew a cache
                    // would answer from it here and be caught.
                    feed.answer(fixture.rejected());
                    assertFailMode(fixture, source, "a vendor that stopped answering, with a prior answer already "
                            + "drawn", fixture.neutral());
                }
                case RENDERS_SNAPSHOT -> {
                    // A mirror's read path renders whatever it committed, whatever its age - that is what makes a
                    // gate decision stand while the vendor is down - so nothing here may fetch and the instant must
                    // stay the one committed beside the data. The age is the reading's business, not the answer's.
                    feed.answer(fixture.rejected());
                    clock.advance(BEYOND_ANY_SNAPSHOT_WINDOW);
                    List<RecordedFeed.Request> before = feed.requests();
                    Object rendered = fixture.ask(source);
                    if (!feed.since(before).isEmpty()) {
                        throw failure(fixture, "spent " + feed.since(before).size() + " request(s) rendering a "
                                + "snapshot that had outlived its refresh window. A render never fetches - the draw "
                                + "is the refresh path's business - or a gate decision stops standing exactly when "
                                + "the vendor is down");
                    }
                    if (!Objects.equals(rendered, fixture.answer())) {
                        throw failure(fixture, "rendered " + rendered + " from an aged snapshot, expected "
                                + fixture.answer() + ". A committed snapshot keeps answering until a refresh replaces "
                                + "it; dropping it because it got old is an outage the mirror invented");
                    }
                    assertInstantHeld(fixture, source, drawn);
                }
            }
        }
    }

    /** The aged leg for a feed that holds what it drew for a declared window: the window is crossed on the clock, the
     *  vendor is broken, and the declared fail mode decides what the caller gets. */
    private static void agedWarmAnswer(SignalFixture fixture, Ticking clock, RecordedFeed feed, SignalSource source,
                                       Instant drawn) {
        Duration window = fixture.warmWindow();
        if (window.isZero() || window.isNegative()) {
            throw failure(fixture, "declares a warm window of " + window + ", which is not a window at all - a feed "
                    + "that holds nothing declares " + SignalFixture.Reads.FETCHES_ON_QUERY);
        }
        clock.advance(window.dividedBy(2));
        List<RecordedFeed.Request> beforeInside = feed.requests();
        Object inside = fixture.ask(source);
        if (!feed.since(beforeInside).isEmpty()) {
            throw failure(fixture, "asked the vendor again half-way through the " + window + " window it declares. "
                    + "The declared window is the promise a metered feed makes about how often it queries, so it is "
                    + "held to on this side as well as the other");
        }
        if (!Objects.equals(inside, fixture.answer())) {
            throw failure(fixture, "answered " + inside + " inside its own window, expected " + fixture.answer());
        }

        feed.answer(fixture.rejected());
        clock.advance(window);
        List<RecordedFeed.Request> beforeAged = feed.requests();
        Object answered = null;
        RuntimeException raised = null;
        try {
            answered = fixture.ask(source);
        } catch (RuntimeException e) {
            raised = e;
        }
        if (feed.since(beforeAged).isEmpty()) {
            // The instants are in the message because this leg has failed once without reproducing - not on a
            // clean tree, and not with the step forced to re-execute on the tree that had just failed. Every
            // hypothesis about it turns on how the source's recorded instant relates to the clock this check
            // advances, and none of that is recoverable from "it did not ask". So the next occurrence carries it.
            throw failure(fixture, "answered without asking the vendor anything although its own " + window
                    + " window had lapsed. Either the window is longer than the fixture declares - in which case the "
                    + "declaration is wrong and this leg proves nothing - or the feed keeps serving what it drew "
                    + "without ever re-asking, which is the defect this leg is here for."
                    + " The clock this check controls read " + drawn + " when the answer was drawn and "
                    + clock.instant() + " when it was asked again, an apparent age of "
                    + Duration.between(drawn, clock.instant()) + " against a declared window of " + window
                    + ". An age at or below the window means the source stamps its freshness from a clock this "
                    + "check does not drive, which would make the leg untestable rather than the source wrong");
        }
        switch (fixture.failMode()) {
            case CLOSED -> {
                if (raised == null) {
                    throw failure(fixture, "answered " + answered + " from an entry that had outlived its own "
                            + window + " window, over a vendor that could not refresh it. A fail-closed feed's answer "
                            + "is read for its EMPTINESS: nobody screened this coordinate since the window lapsed, so "
                            + "an advisory published in the meantime is invisible and the gate reads a confident "
                            + "clean. Clause 4's raise covers the warm cache exactly as it covers the cold one, and "
                            + "it costs nothing the aged answer could still have said - blocking is a superset of "
                            + "whatever that answer would have blocked");
                }
                if (source.freshness().authoritative()) {
                    throw failure(fixture, "still reported itself authoritative after a refresh that did not land. "
                            + "The raise is what a caller acts on, but a console reads this, and a feed that could "
                            + "not reach its vendor is not answering from a fetch");
                }
            }
            case SOFT -> {
                if (raised != null) {
                    throw failure(fixture, "raised " + raised + " over an answer it had already drawn, although it "
                            + "declares a fail-soft mode. The aged value is real evidence that has got older; "
                            + "raising over it blocks on a ranking aid");
                }
                if (Objects.equals(answered, fixture.neutral())) {
                    throw failure(fixture, "dropped to its neutral " + fixture.neutral() + " when the refresh of an "
                            + "answer it had already drawn failed. For a fail-soft signal the neutral value is the "
                            + "LOOSEST one, so discarding aged evidence for it trades real evidence for none in the "
                            + "one direction that matters - it is the fail-closed family that raises here, and for "
                            + "the opposite reason");
                }
                if (!Objects.equals(answered, fixture.answer())) {
                    throw failure(fixture, "answered " + answered + " from the answer it had already drawn, expected "
                            + fixture.answer() + " - the aged value is kept whole or not at all");
                }
                assertInstantHeld(fixture, source, drawn);
            }
        }
    }

    /** Drive one query over a broken vendor and hold the source to its declared fail mode. */
    private static void assertFailMode(SignalFixture fixture, SignalSource source, String situation, Object neutral) {
        Object answered = null;
        RuntimeException raised = null;
        try {
            answered = fixture.ask(source);
        } catch (RuntimeException e) {
            raised = e;
        }
        switch (fixture.failMode()) {
            case CLOSED -> {
                if (raised == null) {
                    throw failure(fixture, "answered " + answered + " under " + situation + " instead of raising");
                }
            }
            case SOFT -> {
                if (raised != null) {
                    throw failure(fixture, "raised " + raised + " under " + situation
                            + " although it declares a fail-soft mode");
                }
                if (!Objects.equals(answered, neutral)) {
                    throw failure(fixture, "answered " + answered + " under " + situation + ", expected the neutral "
                            + neutral + " - a feed that declares every query a fetch may hold nothing back to answer "
                            + "from");
                }
            }
        }
    }

    /** An aged answer may not report itself as freshly fetched: the instant is the consumer's whole protection, so it
     *  stays the one the data was really drawn at. */
    private static void assertInstantHeld(SignalFixture fixture, SignalSource source, Instant drawn) {
        Instant now = source.freshness().refreshed().orElse(null);
        if (!drawn.equals(now)) {
            throw failure(fixture, "reported its last fetch as " + now + " while answering from data drawn at "
                    + drawn + ". An aged answer that stamps itself with the moment it was SERVED renders beside a "
                    + "panel exactly as a fresh one does, and the consumer's only defence - deciding for itself how "
                    + "old is too old - is gone");
        }
    }

    private static void readPathEgress(SignalFixture fixture) {
        if (fixture.vendorHost().isBlank()) {
            throw failure(fixture, "names no vendor host, so the read-purity leg has nothing to hold it to");
        }
        List<String> before = NoEgressResolver.attempted();
        SignalSource source = fixture.source(fixture.production());
        if (!NoEgressResolver.since(before).isEmpty()) {
            throw failure(fixture, "resolved " + NoEgressResolver.since(before) + " while being created; building a "
                    + "client must not contact the vendor");
        }
        List<String> beforeQuery = NoEgressResolver.attempted();
        Object answer = null;
        RuntimeException raised = null;
        try {
            answer = fixture.ask(source);
        } catch (RuntimeException e) {
            raised = e;
        }
        Set<String> reached = new LinkedHashSet<>(NoEgressResolver.since(beforeQuery));

        if (fixture.reads() == SignalFixture.Reads.RENDERS_SNAPSHOT) {
            if (!reached.isEmpty()) {
                throw failure(fixture, "declares that a query renders stored state, yet the query resolved " + reached
                        + ". A read path renders what is durably stored; the fetch is the refresh path's business, so "
                        + "a gate decision stands when the vendor is down");
            }
            return;
        }
        if (!reached.equals(Set.of(fixture.vendorHost()))) {
            throw failure(fixture, "declares that its query fetches from " + fixture.vendorHost() + ", but the query "
                    + "resolved " + reached + ". Either the feed no longer fetches on the read path - in which case "
                    + "declare " + SignalFixture.Reads.RENDERS_SNAPSHOT + " and the kit will hold it to that - or it "
                    + "reaches a host this fixture does not name. If it resolved NOTHING, suspect the JDK's "
                    + "ten-second negative-lookup cache first: something else in this JVM already asked for "
                    + fixture.vendorHost() + ", so the installed resolver was never consulted and this observation "
                    + "is vacuous rather than false");
        }
        // The vendor being unreachable is an outage like any other, so the declared fail mode still governs.
        switch (fixture.failMode()) {
            case CLOSED -> {
                if (raised == null) {
                    throw failure(fixture, "answered " + answer + " with its vendor unreachable instead of raising");
                }
            }
            case SOFT -> {
                if (raised != null) {
                    throw failure(fixture, "raised " + raised + " with its vendor unreachable although it declares a "
                            + "fail-soft mode");
                }
                if (!Objects.equals(answer, fixture.neutral())) {
                    throw failure(fixture, "answered " + answer + " with its vendor unreachable, expected the neutral "
                            + fixture.neutral());
                }
            }
        }
    }

    private static void ecosystemRoundTrip(SignalFixture fixture) throws Exception {
        if (fixture.families().isEmpty() && fixture.uncovered().isEmpty()) {
            for (Class<? extends SignalSource> contract : fixture.signals()) {
                if (COORDINATE_KEYED.contains(contract)) {
                    throw failure(fixture, "declares no coordinate family although it answers "
                            + contract.getSimpleName() + ", which is keyed on an ecosystem and a coordinate. A feed "
                            + "with coordinates has a vocabulary to round-trip; declaring none is opting out of this "
                            + "leg rather than stating a fact about the signal");
                }
            }
            return;
        }
        for (SignalFixture.Family family : fixture.families()) {
            if (!Ecosystems.canonical().contains(family.ecosystem())) {
                throw failure(fixture, "declares the family \"" + family.ecosystem() + "\", which is not one of the "
                        + "canonical ecosystem names. Ecosystems.vocabulary refuses such a key at construction "
                        + "precisely because a misspelling does not fail - it silently stops that ecosystem from "
                        + "ever being screened by this feed");
            }
            try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
                SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
                try {
                    fixture.lookup(source, family.ecosystem(), family.coordinate(), family.version());
                } catch (RuntimeException _) {
                    // The answer is not this leg's subject; the request the lookup issued is.
                }
                List<RecordedFeed.Request> spent = feed.requests();
                if (spent.isEmpty()) {
                    throw failure(fixture, "spent no request looking up a " + family.ecosystem() + " coordinate, "
                            + "although it declares that ecosystem covered");
                }
                // Any request the lookup spent, not merely the first: a lookup may legitimately begin with a request
                // that carries no vocabulary at all - VulnDB mints an OAuth2 bearer before its query, deps.dev walks
                // a resolution chain - and pinning the mark to the first request would be pinning the credential
                // dance rather than the mapping.
                boolean marked = false;
                for (RecordedFeed.Request request : spent) {
                    marked = marked || request.target().contains(family.mark());
                }
                if (!marked) {
                    throw failure(fixture, "looked a " + family.ecosystem() + " coordinate up as "
                            + targets(spent) + ", none of which carries \"" + family.mark() + "\". The "
                            + "vendor's own spelling is what must reach the wire - GitHub calls PyPI \"pip\", and a "
                            + "map that never matches is an ecosystem that is quietly never screened");
                }
            }
        }
        for (String ecosystem : fixture.uncovered()) {
            try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
                SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
                fixture.lookup(source, ecosystem, "example-package", "1.0.0");
                if (!feed.requests().isEmpty()) {
                    throw failure(fixture, "spent a request on " + ecosystem + ", which it declares uncovered. An "
                            + "ecosystem absent from a vocabulary is the \"do not query\" sentinel that keeps a "
                            + "metered call from being spent on a coordinate the vendor could never answer for");
                }
            }
        }
    }

    /**
     *, made executable. The instant is not asserted to <em>exist</em> - {@code SignalSource.freshness()} is
     * abstract, so it always does - but to be <em>earned</em>: a source that has answered nothing must report no
     * fetch instant, and one recorded fetch later it must report one. That pair is what separates a real stamp from
     * a plausible date fabricated at construction, which would render beside an empty panel and read exactly like a
     * healthy feed.
     *
     * <p>Only the instant is asserted here. Whether the value may be acted on is {@link Property#CLEAN_ANSWER_IS_NOT_AN_OUTAGE}'s
     * leg, and deliberately so: a per-key probe feed scopes the authoritative half to the caller's own consultation
     * and hands it out once, so reading it a second time here would consume a window this leg is not testing.
     */
    private static void stalenessStamp(SignalFixture fixture) throws Exception {
        try (RecordedFeed feed = RecordedFeed.serving(fixture.recorded())) {
            SignalSource source = fixture.source(fixture.enabled(feed.endpoint()));
            Freshness cold = source.freshness();
            if (cold.fetched()) {
                throw failure(fixture, "reported a last-fetch instant of " + cold.refreshed().orElseThrow()
                        + " before it had been asked anything at all. A stamp that exists before the first fetch is "
                        + "not a fetch instant - it is a construction date, and it renders beside an empty panel "
                        + "exactly as a healthy feed does");
            }
            if (cold.authoritative()) {
                throw failure(fixture, "reported itself authoritative before it had fetched anything. Freshness.FIXED "
                        + "is for a source with no vendor behind it (a fixed map, a test mirror); a feed that has a "
                        + "vendor and has not reached it yet confirms nothing");
            }
            if (!feed.requests().isEmpty()) {
                throw failure(fixture, "spent " + feed.requests().size() + " request(s) answering freshness(). The "
                        + "accessor renders what the source already holds - a console reads it on a render path, so "
                        + "it must not fetch, refresh or move a stamp");
            }
            ask(fixture, source);
            Freshness warm = source.freshness();
            if (warm.refreshed().isEmpty()) {
                throw failure(fixture, "still reported no last-fetch instant after a successful recorded query. An "
                        + "empty panel must never be ambiguous between \"clean\" and \"never fetched\", which is "
                        + "exactly what a signal with no instant leaves it");
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /**
     * The SPI's own <em>"no feed is active"</em> object for {@code contract}, or {@code null} where the contract
     * declares none. It is what {@link Mutant#A_SOURCE_THAT_ANSWERS_NOTHING} substitutes, and taking it from the SPI
     * rather than writing a stub here is the whole point: the kit's vacuity probe must be the absence the product
     * already declares, not one the test invented and could have got wrong.
     *
     * <p>{@link AdvisorySignal} answers {@code null}: the report-column family has no single neutral instance -
     * "nothing installed" is the empty list its {@code resolve} folds to - so the mutant empties its {@code evaluate}
     * instead, and leaves its name, label and order alone as the identity they are.
     */
    static SignalSource nothingInstalled(Class<?> contract) {
        if (contract == AdvisorySource.class) {
            return AdvisorySource.NONE;
        }
        if (contract == KnownExploitedSource.class) {
            return KnownExploitedSource.NONE;
        }
        if (contract == ExploitProbabilitySource.class) {
            return ExploitProbabilitySource.NONE;
        }
        if (contract == HealthSource.class) {
            return HealthSource.NONE;
        }
        return null;
    }

    /** The contract's own {@code resolve} over a configuration that enables nothing - the neutral element the SPI
     *  declares, resolved through the SPI rather than reconstructed here. */
    private static Object neutral(Class<? extends SignalSource> contract, UnaryOperator<String> config) {
        if (contract == AdvisorySource.class) {
            return AdvisorySource.resolve(config);
        }
        if (contract == KnownExploitedSource.class) {
            return KnownExploitedSource.resolve(config);
        }
        if (contract == ExploitProbabilitySource.class) {
            return ExploitProbabilitySource.resolve(config);
        }
        if (contract == HealthSource.class) {
            return HealthSource.resolve(config);
        }
        if (contract == AdvisorySignal.class) {
            return AdvisorySignal.resolve(config);
        }
        throw new AssertionError("no neutral element is known for " + contract.getName()
                + "; SignalContract.CONTRACTS and this switch must name the same five contracts");
    }

    private static boolean isNeutral(Class<? extends SignalSource> contract, Object resolved) {
        if (contract == AdvisorySignal.class) {
            // The report-column family merges into an ordered list rather than a single neutral instance, so its
            // "nothing installed" is the empty list.
            return resolved instanceof List<?> columns && columns.isEmpty();
        }
        if (contract == AdvisorySource.class) {
            return resolved == AdvisorySource.NONE;
        }
        if (contract == KnownExploitedSource.class) {
            return resolved == KnownExploitedSource.NONE;
        }
        if (contract == ExploitProbabilitySource.class) {
            return resolved == ExploitProbabilitySource.NONE;
        }
        return resolved == HealthSource.NONE;
    }

    /** What a set of requests actually asked for, so a failure names the wire rather than a count. */
    private static String targets(List<RecordedFeed.Request> requests) {
        List<String> targets = new ArrayList<>(requests.size());
        for (RecordedFeed.Request request : requests) {
            targets.add(request.method() + " " + request.target());
        }
        return targets.toString();
    }

    /** Every message in a failure's cause chain, so a check can look for the operator-facing words a bound owes
     *  without caring which layer wrapped it. */
    private static String chain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append(" | ");
            if (current.getCause() == current) {
                break;
            }
        }
        return messages.toString();
    }

    private static AssertionError failure(SignalFixture fixture, String detail) {
        return new AssertionError("The " + fixture.signal() + " signal (" + fixture.providerClass() + ") "
                + detail + ".");
    }
}
