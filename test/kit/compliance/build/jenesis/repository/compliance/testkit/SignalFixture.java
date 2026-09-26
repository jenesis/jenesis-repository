package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * How one {@link SignalSourceProvider} registers with the shared {@link SignalContract} suite: a fixture points its
 * vendor at a {@link RecordedFeed}, hands the kit the recorded payload its parser really reads, states the one
 * question the kit should ask the created source and the answer it owes, and <em>declares the four things the contract
 * cannot infer</em> - the fail mode a failed fetch takes, where a query's data comes from, whether the feed paginates
 * at all, and how a consumer tells an outage from a genuinely clean coordinate. The kit then drives every contract
 * property against it. Staleness is no longer among them: {@code SignalSource.freshness()} is abstract, so every
 * source has one and the kit asserts the real instant rather than a declaration about it.
 *
 * <h2>The fixture drives the instance a gate reaches</h2>
 * {@link #source(UnaryOperator)} does not construct anything: it resolves through
 * {@link SignalSourceProvider#named}, the same discovery a {@code ComplianceScreen} uses, and picks the entry keyed by
 * this fixture's {@link #signal()}. So the object under contract is the one a publish request meets, the lookup
 * doubles as proof that the fixture's test module really roots the provider's module, and the configuration the
 * fixture writes is the configuration an operator writes.
 *
 * <h2>The four declarations that carry the fixture's honesty</h2>
 * Each is machine-checked rather than trusted, because a declaration nothing verifies is a comment:
 * <ul>
 *   <li>{@link #failMode()} is proven by answering the fixture's own <em>good</em> payload behind a rejected status.
 *       A {@link FailMode#CLOSED} feed must raise; a {@link FailMode#SOFT} one must answer {@link #neutral()} - and
 *       because the body under the bad status really would parse, a feed that lost its status branch is caught
 *       returning data rather than passing an empty-body check.</li>
 *   <li>{@link #reads()} is proven by counting the requests one query spends and by driving the source at its
 *       <em>production</em> endpoint under the {@link NoEgressResolver} tripwire. A feed declaring
 *       {@link Reads#RENDERS_SNAPSHOT} must reach for nothing; one declaring {@link Reads#FETCHES_ON_QUERY} must be
 *       caught reaching for the host it names, by name. The declaration is therefore a claim about &sect;10 that the
 *       kit can falsify in either direction.</li>
 *   <li>{@link #paging()} is proven by an endless recording. A {@link Paging#CURSOR_CAPPED} feed must fail with a
 *       named cap and expose no partial answer; a {@link Paging#SINGLE_DOCUMENT} one must be shown to spend exactly
 *       one request and a {@link Paging#CHAINED} one exactly its declared number of forward steps, so neither value
 *       can be used to opt out of the leg.</li>
 *   <li>{@link #degradation()} is proven by the vendor's own {@link #clean()} answer. It must yield
 *       {@link #cleanAnswer()} <em>without</em> raising - a coordinate the vendor carries nothing for is an answer,
 *       not a failure - and that answer must then stand in the declared relation to {@link #neutral()}: identical for
 *       a feed whose outage raises, and different for one whose contract carries a companion. There is no third
 *       value, which is the point: a fail-soft feed has nowhere to declare that it cannot tell the two apart.</li>
 * </ul>
 *
 * <p>Staleness is not declared and not excused. Every {@link SignalSource} carries {@code freshness()}, so the kit's
 * staleness leg drives the source cold, asserts it reports no fetch instant, drives one recorded fetch through it and
 * asserts an instant appeared - the fact itself rather than a fixture's claim about it.
 *
 * <p>A fixture owns whatever it builds. {@link #start()} runs once per suite and {@link #close()} once after it;
 * constructing a fixture must allocate nothing, because the census instantiates every fixture purely to read
 * {@link #providerClass()}.
 */
public interface SignalFixture extends AutoCloseable {

    /** What a consumer must see when the vendor cannot answer - a property of the signal, not of the vendor. */
    enum FailMode {

        /** A failed or rejected fetch is raised. The mode every advisory feed takes, because an empty advisory list
         *  reads as "this package is clean" and a vulnerability nobody reported is indistinguishable from none. */
        CLOSED,

        /** A failed fetch degrades to {@link SignalFixture#neutral()} - the mode a ranking aid or a fail-soft
         *  catalogue takes, where an absent signal is safe and blocking every publish on a vendor outage is not. The
         *  neutral answer must still be distinguishable from a real one, which is what {@link #neutral()} pins. */
        SOFT
    }

    /** Where the data a query answers from comes from - the &sect;10 read-purity question, stated per feed. */
    enum Reads {

        /**
         * The query renders durably stored state and fetches nothing at all; refreshing is a separate, explicit
         * entry point. This is the whole of what {@code SignalSourceProvider}'s read-purity clause promises, and the
         * kit asserts it by driving the source at its production endpoint and requiring that no vendor host is
         * reached even when the snapshot space is empty.
         */
        RENDERS_SNAPSHOT,

        /**
         * The query renders a durable snapshot while it is inside its refresh window and performs the refresh
         * <em>itself</em> when it is not. Half of &sect;10: the answer survives a restart and reports the age that was
         * committed with the data, so a second replica and a re-render cost no upstream call - but the fetch is still
         * not a separate write-role entry point, so the first query after the window lapses does pay for it and a
         * cold deployment still depends on the vendor.
         *
         * <p>The kit tells this apart from {@link #WARM_THEN_RENDERS} by the one observation that distinguishes a
         * stored answer from a remembered one: a source created <em>afresh</em> over the same snapshot space must
         * answer without a request, which a process-local cache can never do.
         */
        SNAPSHOT_WITH_LAZY_REFRESH,

        /**
         * The first query fetches and the answer is then held <em>in memory</em> for a TTL; subsequent queries inside
         * that window render it without a request. The gate's warm-read behaviour, but not &sect;10's: the warmth
         * belongs to the process, so a restart re-fetches and a cold gate decision still depends on the vendor. The
         * kit asserts both halves - the warm read spends no request, and a freshly created source does.
         */
        WARM_THEN_RENDERS,

        /**
         * Every query fetches. The read path <em>is</em> the fetch path, so a gate decision does not stand when the
         * vendor is down. Asserted positively: two queries spend two requests, and a query against the production
         * endpoint is caught resolving {@link SignalFixture#vendorHost()}.
         */
        FETCHES_ON_QUERY
    }

    /** Whether the feed draws a paginated answer, and therefore whether a page cap can bind on it. */
    enum Paging {

        /** The feed follows a cursor and stops at a cap. An over-long recording must produce a named failure and no
         *  partial answer. */
        CURSOR_CAPPED,

        /** The feed answers one document per lookup - there is no cursor to cap. Asserted rather than assumed: one
         *  cold lookup must spend exactly one request, so this value cannot be used to skip the leg. */
        SINGLE_DOCUMENT,

        /**
         * One lookup is a fixed <em>chain</em> of separate bounded fetches, each asking a question the previous
         * answer named - deps.dev resolves a coordinate's default version, that version's source repository and only
         * then that repository's Scorecard. A chain is N queries, not N pages of one, so there is no cursor and
         * nothing for a page cap to bind on; the bound is the chain's own declared length. Asserted rather than
         * assumed: a cold lookup must spend exactly {@link #chainedFetches()} requests <em>and</em> they must all
         * address different things, because a chain resolves forward and a repeated target is a retry loop nothing
         * here would cap.
         */
        CHAINED
    }

    /**
     * <em>How</em> a consumer tells "the vendor answered, and it carries nothing for this coordinate" from "the vendor
     * could not be reached" - gate 4 stated per signal. It is the one question the whole kit exists for: a signal that
     * reports nothing known when it actually failed is indistinguishable from a clean package, and every consuming
     * decision (a publish that passes, a hold the re-analysis sweep auto-releases) turns on the difference.
     *
     * <p><strong>There is deliberately no value for "it cannot".</strong> That shape was real for two of the twelve
     * signals until, and having nowhere to declare it is what stops it coming back: a fail-soft signal can only
     * declare {@link #FLAGGED}, and the kit then demands a clean answer that differs from the neutral one - which it
     * can only produce by reading {@code SignalSource.freshness()}. A signal with no separation left to declare fails
     * that leg with a message naming the companion it is missing, rather than declaring the defect and passing.
     */
    enum Degradation {

        /**
         * An outage <em>raises</em>, so the neutral answer can only ever have come from the vendor saying so. The
         * position of every fail-closed advisory feed, and the reason {@link FailMode#CLOSED} exists; the kit holds
         * such a fixture to a clean answer that really is the neutral value, so the raise carries the whole
         * separation.
         */
        RAISED,

        /**
         * An outage degrades to a neutral answer <em>and</em> the contract carries a companion the consumer reads to
         * tell it from a clean one - {@code SignalSource.freshness()}'s authoritative half, which separates "not
         * listed" from "not listed because the catalogue could not be consulted". The fixture's {@link #ask} must
         * carry that companion, so the kit sees a clean answer that differs from the neutral one.
         */
        FLAGGED
    }

    /**
     * One coordinate family this feed covers, and the mark its vendor's own spelling of the ecosystem leaves on the
     * wire - {@code ecosystem=pip} for GitHub's PyPI, a {@code pkg:maven/...} purl segment for Snyk. The kit drives a
     * lookup for the coordinate and matches the mark against the decoded request, so the mapping is proven where it
     * actually matters rather than by re-reading the vocabulary's own map back to itself.
     */
    record Family(String ecosystem, String coordinate, String version, String mark) {

        public Family {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(mark, "mark");
        }
    }

    /** One configuration under which this provider must decline, and why it must - the reason is asserted non-blank
     *  and reported when the decline does not happen, so a self-skip leg names the shape it was testing. */
    record Unconfigured(String why, UnaryOperator<String> config) {

        public Unconfigured {
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(config, "config");
        }
    }

    /** The fully qualified {@link SignalSourceProvider} implementation this fixture covers, as the census parses it
     *  out of the feed module's {@code provides ... with ...} clause. */
    String providerClass();

    /** The signal name the provider answers to ({@code osv}, {@code kev}) - the enablement key, the attribution key
     *  and the sub-space its snapshots would live in. */
    String signal();

    /** The {@link SignalSource} contracts the created source must answer; cross-checked against the provider's own
     *  {@code signals()} declaration and against what the created object really is. */
    Set<Class<? extends SignalSource>> signals();

    /** The vendor host this feed's <em>production</em> configuration resolves - the host the read-purity leg expects
     *  to catch a fetching read path reaching for, named here so the failure says which vendor a query went to. */
    String vendorHost();

    FailMode failMode();

    Reads reads();

    Paging paging();

    /** Whether an outage is distinguishable from a coordinate the vendor genuinely carries nothing for, and how. */
    Degradation degradation();

    /** The configuration that enables this feed and points it at the recorded endpoint. */
    UnaryOperator<String> enabled(URI recorded);

    /** The configuration that enables this feed against its <em>production</em> default - no endpoint override, so
     *  the feed builds the URL it ships with. Drives the read-purity leg, and nothing else. */
    UnaryOperator<String> production();

    /** Every configuration under which the provider must yield empty rather than a half-built source. At least the
     *  switched-off shape; a licensed feed adds its enabled-but-uncredentialed one. */
    List<Unconfigured> unconfigured();

    /** The recorded good answer - a faithful payload from {@code RecordedFeeds}, played back for every request. */
    RecordedFeed.Responder recorded();

    /** The vendor's rejection: a non-200 whose body is the <em>good</em> payload, so a lost status branch is caught
     *  answering with real data rather than by an empty-body check that any error page would satisfy. */
    RecordedFeed.Responder rejected();

    /** A 200 whose body this feed cannot make sense of - a proxy's HTML error page, a foreign document, a catalogue
     *  with nothing in it. */
    RecordedFeed.Responder malformed();

    /**
     * The vendor's own <em>"I carry nothing for this"</em> answer - an empty advisory array, a catalogue that lists
     * other CVEs than the queried one, the 404 a public dataset answers for a coordinate it does not know. It is the
     * counterpart of {@link #rejected()} and the reason the kit can say anything about gate 4 at all: without a
     * recording of a genuinely clean lookup, "nothing known" would only ever be observed on the failure paths, where
     * it is exactly what must not appear.
     */
    RecordedFeed.Responder clean();

    /** What {@link #ask} yields over {@link #clean()}. For a {@link Degradation#RAISED} feed this <em>is</em>
     *  {@link #neutral()}; for a {@link Degradation#FLAGGED} one it must differ from it, which is the companion
     *  showing through. */
    Object cleanAnswer();

    /** A recording that never stops advertising another page, each page carrying data a partial answer would leak.
     *  Only consulted for {@link Paging#CURSOR_CAPPED}. */
    default RecordedFeed.Responder endless() {
        throw new UnsupportedOperationException(signal() + " declares " + paging()
                + ", so the kit never asks it for an endless recording");
    }

    /** A substring the page-cap failure must carry, in the words an operator reads. Only consulted for
     *  {@link Paging#CURSOR_CAPPED}. */
    default String pageCapMarker() {
        throw new UnsupportedOperationException(signal() + " declares " + paging()
                + ", so the kit never asks it for a page-cap marker");
    }

    /** How many separate bounded fetches one cold lookup makes. Only consulted for {@link Paging#CHAINED}, where it
     *  is the declared bound the kit holds the chain to. */
    default int chainedFetches() {
        throw new UnsupportedOperationException(signal() + " declares " + paging()
                + ", so the kit never asks it for a chain length");
    }

    /**
     * How long an answer this feed has already drawn keeps being served without asking the vendor again. Only
     * consulted for {@link Reads#WARM_THEN_RENDERS} and {@link Reads#SNAPSHOT_WITH_LAZY_REFRESH}, and held to on
     * <em>both</em> sides, so it cannot be a comfortable over-estimate: half of it in, the feed must still answer
     * without a request; past it, the feed must ask the vendor again. That makes the window a product fact the kit
     * can falsify rather than a constant buried in a source, and it is what lets the aged-answer leg reach the
     * aged-answer case without waiting six hours for it.
     */
    default Duration warmWindow() {
        throw new UnsupportedOperationException(signal() + " declares " + reads()
                + ", so the kit never asks it how long it holds an answer it drew");
    }

    /** Ask the created source this fixture's own question and reduce the answer to something comparable. The kit
     *  compares it with {@link #answer()} and {@link #neutral()} and never interprets it. */
    Object ask(SignalSource source);

    /** What {@link #ask} must yield over {@link #recorded()} - the recorded advisory, the catalogue membership, the
     *  score. Asserted to differ from {@link #neutral()}, so no leg of this contract can pass vacuously. */
    Object answer();

    /** What {@link #ask} must yield when the vendor could not be read: an empty list, an empty map, "not listed and
     *  not authoritative". For a {@link FailMode#CLOSED} feed this is the answer that must <em>never</em> appear in
     *  place of a failure. */
    Object neutral();

    /** Drive one coordinate lookup, ignoring the answer, so the kit can read the request off the wire. Called only
     *  for a feed that declares coordinate families. */
    default void lookup(SignalSource source, String ecosystem, String coordinate, String version) {
        throw new UnsupportedOperationException(signal() + " declares no coordinate family, so the kit never asks it "
                + "to look one up");
    }

    /** The coordinate families this feed covers, each with the mark its vendor's spelling leaves on the wire. Empty
     *  for a CVE-keyed signal, which the census then holds to having no ecosystem-keyed contract at all. */
    List<Family> families();

    /** Ecosystems this vendor does not cover: the lookup must spend no request whatsoever - the {@code Vocabulary}'s
     *  "absent means do not query" sentinel, proven at the wire rather than at the map. */
    List<String> uncovered();

    /**
     * The source under contract: resolved through {@link SignalSourceProvider#named}, the SPI's own discovery, over
     * the given effective configuration - so the fixture drives the instance a gate reaches, the configuration is the
     * one an operator writes, and the lookup doubles as proof that the fixture's test module roots the provider's
     * module. {@link SignalContext} is built inside {@code named} exactly as it is in production; a suite that binds
     * no {@link SignalContext#deployment deployment} therefore also proves that creation never touched the snapshot
     * space, because touching it would throw.
     */
    default SignalSource source(UnaryOperator<String> config) {
        for (Class<? extends SignalSource> contract : signals()) {
            SignalSource resolved = SignalSourceProvider.named(contract, config).get(signal());
            if (resolved != null) {
                return resolved;
            }
        }
        throw new AssertionError(providerClass() + " created no source for the " + signal() + " signal under a "
                + "configuration that enables it. Either the fixture's test module does not require the module that "
                + "provides it, or the provider declined a configuration it should have accepted.");
    }

    /** Build whatever the fixture owns. Called once, before any check runs; a failure here is a test failure, never
     *  a skip - a recorded fixture has no environment to be missing. */
    default void start() throws Exception {
    }

    @Override
    default void close() throws Exception {
    }
}
