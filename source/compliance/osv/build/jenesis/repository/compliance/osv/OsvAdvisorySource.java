package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.FeedCache;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.store.ArtifactStore;

/**
 * An {@link AdvisorySource} over OSV (osv.dev). For each coordinate it posts {@code /v1/query} for the package at the
 * requested version and maps every vulnerability to an {@link Advisory} as {@link OsvRecords} reads one.
 *
 * <p>Transport is the {@link FeedClient}'s - client and timeouts, status handling, whole-fetch deadline, byte cap,
 * retries, page cap and fail-closed policy. OSV's own half is the URL, the query body, the {@code next_page_token}
 * cursor (echoed back as {@code page_token}) and the field mapping. Every page is drawn before the reader completes, so
 * an advisory on page two is never invisible, and a feed that never stops paging fails at the cap.
 *
 * <p>The network operation sits behind an {@link Endpoint} seam, so recorded payloads drive parsing and scoring through
 * the same client, caps and pagination as the live query. A failed query throws, so the gate fails closed.
 *
 * <p>Lookups sit behind a {@link FeedCache#failClosed fail-closed} {@link FeedCache}: a version screened again within
 * the window answers from memory, a cold burst on one coordinate is one query, and a failed refresh past the window
 * raises. OSV meters nothing, so the hour-long window buys burst collapsing and outage isolation while a new advisory
 * reaches the gate within the hour.
 *
 * <p>Asked about many versions at once ({@link AdvisorySource.Batched}), it posts {@code /v1/querybatch} for those
 * the cache does not hold, a thousand to a request, and fetches each record the answers name once from
 * {@code /v1/vulns/<id>}, keyed by its modification instant so a revised record is fetched again; every answer lands in
 * the same cache a single query fills. A query OSV answers only in part is asked on its own.
 *
 * <p>It publishes what changed ({@link AdvisorySource.Changes}): each ecosystem's change list in OSV's export, drawn
 * into a log in the feed's signal space ({@link OsvChanges}), from which a scan asks again only about the packages
 * whose records changed.
 */
public final class OsvAdvisorySource implements AdvisorySource.Batched, AdvisorySource.Changes {

    /** How long one coordinate version's answer is served before OSV is asked again. */
    private static final Duration TTL = Duration.ofHours(1);

    /** The single network operation, isolated so a test answers with a fixed response. The argument is the request body
     *  the feed built, carrying {@code page_token} from the second page on. */
    @FunctionalInterface
    public interface Endpoint {
        String query(String body) throws IOException;
    }

    /** The feed's name - the attribution key its provider answers to and the client names in every failure. */
    private static final String FEED = "osv";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.osv.dev");

    /** Where OSV publishes its export, each ecosystem's change list among it. */
    public static final URI DEFAULT_EXPORT = URI.create("https://osv-vulnerabilities.storage.googleapis.com/");

    /** The space of a source built with none: a draw or a read of the log is a wiring error there. */
    private static final Supplier<ArtifactStore> NO_SPACE = () -> {
        throw new IllegalStateException("This OSV source was built without a signal space to keep its change log in");
    };

    /** A connect timeout, so a black-holed host (a dropped SYN, no RST) fails the fetch rather than parking the gate
     *  thread. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A gating feed, so the client's fail-closed defaults unchanged: 30 s per request, a 5 min whole-fetch deadline,
     *  50 pages, 3 attempts with backoff honouring {@code Retry-After}, a capped body, and a same-origin cursor. */
    private static final FeedPolicy POLICY = FeedPolicy.closed();

    /** A test's stand-in for OSV's endpoints: the body of the answer to {@code request}. */
    @FunctionalInterface
    public interface Exchange {
        String answer(FeedRequest request) throws IOException;
    }

    private final FeedClient client;
    private final URI query;
    private final URI querybatch;
    private final URI vulns;
    private final FeedCache<List<Advisory>> cache;
    private final FeedCache<JsonNode> records;
    private final OsvChanges changes;
    private final OsvQuery.Shared shared;

    public OsvAdvisorySource() {
        this(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), DEFAULT_ENDPOINT, DEFAULT_EXPORT,
                NO_SPACE, Clock.systemUTC(), OsvQuery.Shared.none());
    }

    public OsvAdvisorySource(Endpoint endpoint) {
        this(FeedClient.of(FEED, transport(endpoint), POLICY), DEFAULT_ENDPOINT, DEFAULT_EXPORT, NO_SPACE,
                Clock.systemUTC(), OsvQuery.Shared.none());
    }

    private OsvAdvisorySource(FeedClient client, URI base, URI export, Supplier<ArtifactStore> space, Clock clock,
                              OsvQuery.Shared shared) {
        this.client = client;
        this.shared = shared;
        this.query = base.resolve("/v1/query");
        this.querybatch = base.resolve("/v1/querybatch");
        this.vulns = base.resolve("/v1/vulns/");
        this.cache = FeedCache.failClosed("OSV", this::query, TTL, clock);
        this.records = FeedCache.failClosed("OSV", this::record, TTL, clock);
        this.changes = new OsvChanges(client, export, vulns, space, clock);
    }

    /** A source answering every request through {@code exchange}, as a 200 the client bounds and pages as a live
     *  one. */
    public static OsvAdvisorySource exchanging(Exchange exchange) {
        return exchanging(exchange, NO_SPACE, Clock.systemUTC());
    }

    /** As {@link #exchanging(Exchange)}, keeping its change log in {@code space} on {@code clock}. */
    public static OsvAdvisorySource exchanging(Exchange exchange, Supplier<ArtifactStore> space, Clock clock) {
        return responding(request -> FeedResponse.of(200, exchange.answer(request)), space, clock);
    }

    /** A test's stand-in for OSV's endpoints that answers a whole response, its status among it. */
    @FunctionalInterface
    public interface Responder {
        FeedResponse answer(FeedRequest request) throws IOException;
    }

    /** A source answering every request through {@code responder}, keeping its change log in {@code space} on
     *  {@code clock}. */
    public static OsvAdvisorySource responding(Responder responder, Supplier<ArtifactStore> space, Clock clock) {
        return new OsvAdvisorySource(FeedClient.of(FEED, (request, timeout) -> responder.answer(request), POLICY),
                DEFAULT_ENDPOINT, DEFAULT_EXPORT, space, clock, OsvQuery.Shared.none());
    }

    /** As {@link #exchanging(Exchange)}, sharing its answers through {@code shared}. */
    public static OsvAdvisorySource exchanging(Exchange exchange, OsvQuery.Shared shared) {
        return new OsvAdvisorySource(FeedClient.of(FEED, (request, timeout) -> FeedResponse.of(200,
                exchange.answer(request)), POLICY), DEFAULT_ENDPOINT, DEFAULT_EXPORT, NO_SPACE, Clock.systemUTC(),
                shared);
    }

    /** The production form, over the deployment clock the reading's retry window is measured on. */
    public static OsvAdvisorySource over(URI base, URI export, Supplier<ArtifactStore> space, Clock clock) {
        return new OsvAdvisorySource(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), base, export,
                space, clock, OsvQuery.Shared.decision());
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        if (!OsvQuery.covers(ecosystem)) {
            return List.of();
        }
        return cache.get(key(ecosystem, coordinate, version));
    }

    @Override
    public List<List<Advisory>> advisories(List<AdvisorySource.Query> queries) {
        List<List<Advisory>> answers = new ArrayList<>(Collections.nCopies(queries.size(), List.<Advisory>of()));
        List<Integer> asked = new ArrayList<>();
        Set<String> pending = new HashSet<>();
        for (int at = 0; at < queries.size(); at++) {
            AdvisorySource.Query query = queries.get(at);
            if (!OsvQuery.covers(query.ecosystem())) {
                continue;
            }
            String key = key(query.ecosystem(), query.coordinate(), query.version());
            if (cache.fresh(key).isEmpty() && pending.add(key)) {
                asked.add(at);
            }
        }
        for (int from = 0; from < asked.size(); from += OsvQuery.BATCH_LIMIT) {
            List<Integer> chunk = asked.subList(from, Math.min(asked.size(), from + OsvQuery.BATCH_LIMIT));
            List<OsvQuery.Listed> listed = OsvQuery.ask(client, querybatch, chunk.stream().map(queries::get).toList());
            for (int i = 0; i < chunk.size(); i++) {
                AdvisorySource.Query query = queries.get(chunk.get(i));
                String key = key(query.ecosystem(), query.coordinate(), query.version());
                if (listed.get(i).more()) {
                    // More records than a batch answer carries: the query's own pages are drawn instead.
                    cache.get(key);
                    continue;
                }
                List<Advisory> found = new ArrayList<>();
                for (OsvQuery.Record named : listed.get(i).records()) {
                    OsvRecords.advisory(records.get(named.id() + " " + named.modified()), query.coordinate())
                            .ifPresent(found::add);
                }
                cache.put(key, List.copyOf(found));
            }
        }
        // Every query asked is now held, a repeated one included, so each position reads its answer back.
        for (int at = 0; at < queries.size(); at++) {
            AdvisorySource.Query query = queries.get(at);
            if (OsvQuery.covers(query.ecosystem())) {
                answers.set(at, cache.get(key(query.ecosystem(), query.coordinate(), query.version())));
            }
        }
        return List.copyOf(answers);
    }

    @Override
    public int drawChanges() throws IOException {
        return changes.draw();
    }

    @Override
    public AdvisorySource.ChangeLog changes(long after) throws IOException {
        return changes.changes(after);
    }

    /** Every held answer for a version of {@code packages} - the key's ecosystem and coordinate, ahead of its version. */
    @Override
    public void forget(Set<AdvisorySource.Package> packages) {
        Set<String> stale = new HashSet<>();
        packages.forEach(named -> stale.add(named.ecosystem() + " " + named.coordinate()));
        cache.forget(key -> stale.contains(key.substring(0, Math.max(0, key.lastIndexOf(' ')))));
    }

    /** One record in full, the record cache's loader; the key is the id and the modification instant the batch named,
     *  so a revised record is a different key. */
    private JsonNode record(String key) throws IOException {
        return OsvQuery.fetch(client, vulns, key.substring(0, key.indexOf(' ')));
    }

    /** The cache key: the three coordinates joined by spaces, so it reads well in the cache's failure. A coordinate
     *  and a version never contain one, so the key reads back from its end. */
    private static String key(String ecosystem, String coordinate, String version) {
        return ecosystem + " " + coordinate + " " + version;
    }

    /** One coordinate version's query, the cache's loader: every page drawn through the shared client. */
    private List<Advisory> query(String key) throws IOException {
        int last = key.lastIndexOf(' '), middle = key.lastIndexOf(' ', last - 1);
        String ecosystem = key.substring(0, middle), coordinate = key.substring(middle + 1, last),
                version = key.substring(last + 1);
        try {
            List<Advisory> advisories = new ArrayList<>();
            for (JsonNode vuln : shared.answered(client, query, ecosystem, coordinate, version)) {
                OsvRecords.advisory(vuln, coordinate).ifPresent(advisories::add);
            }
            return List.copyOf(advisories);
        } catch (FeedException e) {
            throw new IOException(OsvQuery.reason(e), e);
        }
    }

    /** The ecosystems OSV publishes that the product asks it about. */
    @Override
    public Set<String> ecosystems() {
        return OsvQuery.covered();
    }

    /** Display-only for this fail-closed feed, so no caller misreads a degraded value. It is the cache's own account:
     *  while a coordinate this feed could not screen is in its retry window the reading is not authoritative, clearing
     *  when it screens again or the window lapses. */
    @Override
    public Freshness freshness() {
        return cache.freshness();
    }

    /** The {@link Endpoint} seam as a transport: a recorded body answered as a 200, bounded, screened and paginated by
     *  the client exactly as a live one. */
    private static FeedTransport transport(Endpoint endpoint) {
        return (request, timeout) -> FeedResponse.of(200, endpoint.query(request.body()));
    }
}
