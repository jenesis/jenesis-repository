package build.jenesis.repository.compliance.openssf;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.FreshnessTracker;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.feed.Osv;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.compliance.osv.OsvChanges;
import build.jenesis.repository.compliance.osv.OsvQuery;
import build.jenesis.repository.store.ArtifactStore;

/**
 * An {@link AdvisorySource} over the curated OpenSSF malicious-packages dataset (github.com/ossf/malicious-packages,
 * Apache-2.0), consumed through the OSV.dev API that serves it. For each coordinate it queries {@code /v1/query} for
 * the package in the given ecosystem at the requested version and keeps only the dataset's own {@code MAL-} records,
 * mapping each to a malicious {@link Advisory} with its CVE aliases and the fixed version where the record ends a
 * compromised range. A curated record carries no usable CVSS score, so the severity is the reviewer's
 * {@code database_specific.severity} word when present and {@link Severity#NONE} otherwise - the gate acts on the
 * malicious flag. Beside the full {@code osv} feed the same {@code MAL-} advisory is counted once
 * ({@code AdvisorySource.combined} de-duplicates by id), so a deployment can screen for curated malware without the
 * whole vulnerability feed.
 *
 * <p>The transport - timeouts, deadline, status handling, byte and page caps, retries honouring {@code Retry-After} and
 * the fail-closed policy - is the {@link FeedClient}'s; this class keeps the query URL, the body, the
 * {@code next_page_token} cursor and the field mapping. The client draws every page before the reader completes, so a
 * record on page two of a widely-affected package is seen, and a feed that never stops handing back a token fails at
 * the page cap rather than answering incompletely.
 *
 * <p>The network operation sits behind an {@link Endpoint} seam, so recorded payloads travel through the same client,
 * caps and pagination as a live answer. A failed query throws, so the gate fails closed. This source holds no
 * {@code FeedCache} of answers; a query the vulnerability feed asked the same endpoint within the gate's decision in
 * progress is answered from its answer ({@link OsvQuery.Shared}), so a copy screened by both costs OSV one query.
 *
 * <p>Asked about many versions at once ({@link AdvisorySource.Batched}), it posts {@code /v1/querybatch}, a thousand to
 * a request, and fetches in full only the {@code MAL-} records the answers name - each once per batch, keyed by its
 * modification instant - so a clean package costs a share of one request rather than a query of its own. A query OSV
 * answers only in part is asked on its own.
 *
 * <p>It publishes what it changed ({@link AdvisorySource.Changes}) from OSV's own change lists, since its records are
 * OSV's: a draw reads each ecosystem's {@code modified_id.csv} at the OSV export and keeps the {@code MAL-} records,
 * committing the packages they affect to the feed's own log, so a package flagged malicious after its copy was cached
 * is asked again by the next scan without waiting for the full pass.
 */
public final class OpenSsfMaliciousSource implements AdvisorySource.Batched, AdvisorySource.Changes {

    /** The single network operation, isolated so a test can answer with a fixed response. The argument is the
     *  request body the feed built (the JSON query, carrying {@code page_token} from the second page on). */
    @FunctionalInterface
    public interface Endpoint {
        String query(String body) throws IOException;
    }

    /** The feed's name - the attribution key its provider answers to and the client names in every failure. */
    private static final String FEED = "openssf";

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.osv.dev");

    /** A bounded connect timeout so a black-holed feed host (a firewall dropping the SYN, no RST) fails the fetch
     *  instead of parking the gate thread that asked for the lookup. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A malicious-package feed gates, so it takes the client's fail-closed defaults unchanged. */
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
    private final FreshnessTracker fetches;
    private final OsvQuery.Shared shared;
    private final OsvChanges changes;

    public OpenSsfMaliciousSource() {
        this(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC(),
                OsvQuery.Shared.none());
    }

    public OpenSsfMaliciousSource(Endpoint endpoint) {
        this(FeedClient.of(FEED, transport(endpoint), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC(),
                OsvQuery.Shared.none());
    }

    private OpenSsfMaliciousSource(FeedClient client, URI base, Clock clock, OsvQuery.Shared shared) {
        this(client, base, OsvAdvisorySource.DEFAULT_EXPORT, NO_SPACE, clock, shared);
    }

    private OpenSsfMaliciousSource(FeedClient client, URI base, URI export, Supplier<ArtifactStore> space, Clock clock,
                                   OsvQuery.Shared shared) {
        this.client = client;
        this.shared = shared;
        this.changes = OsvChanges.of(client, export, base.resolve("/v1/vulns/"), space, clock,
                id -> id.startsWith("MAL-"));
        this.query = base.resolve("/v1/query");
        this.querybatch = base.resolve("/v1/querybatch");
        this.vulns = base.resolve("/v1/vulns/");
        this.fetches = new FreshnessTracker(clock);
    }

    /** A source answering every request through {@code exchange}, as a 200 the client bounds and pages as a live
     *  one. */
    public static OpenSsfMaliciousSource exchanging(Exchange exchange) {
        return exchanging(exchange, OsvQuery.Shared.none());
    }

    /** As {@link #exchanging(Exchange)}, sharing its answers through {@code shared}. */
    public static OpenSsfMaliciousSource exchanging(Exchange exchange, OsvQuery.Shared shared) {
        return new OpenSsfMaliciousSource(FeedClient.of(FEED, (request, timeout) -> FeedResponse.of(200,
                exchange.answer(request)), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC(), shared);
    }

    /** The production form, over the deployment clock the reading's retry window is measured on. */
    public static OpenSsfMaliciousSource over(URI base, Clock clock) {
        return over(base, OsvAdvisorySource.DEFAULT_EXPORT, NO_SPACE, clock);
    }

    /** The production form, drawing OSV's change lists at {@code export} into the log kept in {@code space}. */
    public static OpenSsfMaliciousSource over(URI base, URI export, Supplier<ArtifactStore> space, Clock clock) {
        return new OpenSsfMaliciousSource(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), base, export,
                space, clock, OsvQuery.Shared.decision());
    }

    /** As {@link #exchanging(Exchange)}, keeping its change log in {@code space} on {@code clock}. */
    public static OpenSsfMaliciousSource exchanging(Exchange exchange, Supplier<ArtifactStore> space, Clock clock) {
        return new OpenSsfMaliciousSource(FeedClient.of(FEED, (request, timeout) -> FeedResponse.of(200,
                exchange.answer(request)), POLICY), DEFAULT_ENDPOINT, OsvAdvisorySource.DEFAULT_EXPORT, space, clock,
                OsvQuery.Shared.none());
    }

    /** No space to keep a change log in: a source built where no deployment bound one still screens. */
    private static final Supplier<ArtifactStore> NO_SPACE = () -> {
        throw new IllegalStateException("the malicious-package feed was built with no space for its change log");
    };

    @Override
    public int drawChanges() throws IOException {
        return changes.draw();
    }

    @Override
    public AdvisorySource.ChangeLog changes(long after) throws IOException {
        return changes.changes(after);
    }

    /** Nothing to drop: the source holds no answers of its own, and what it shares lasts seconds. */
    @Override
    public void forget(Set<AdvisorySource.Package> packages) {
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        if (!OsvQuery.covers(ecosystem)) {
            return List.of();
        }
        String key = ecosystem + '|' + coordinate + '|' + version;
        try {
            List<Advisory> advisories = new ArrayList<>();
            for (JsonNode vuln : shared.answered(client, query, ecosystem, coordinate, version)) {
                malicious(vuln, coordinate).ifPresent(advisories::add);
            }
            fetches.fetched(key);
            return advisories;
        } catch (FeedException e) {
            fetches.failed(key);
            throw new UncheckedIOException("Failed to query the malicious-package feed for "
                    + ecosystem + " " + coordinate + " " + version + " (" + OsvQuery.reason(e) + ")", e);
        }
    }

    @Override
    public List<List<Advisory>> advisories(List<AdvisorySource.Query> queries) {
        List<List<Advisory>> answers = new ArrayList<>(Collections.nCopies(queries.size(), List.<Advisory>of()));
        List<Integer> asked = new ArrayList<>();
        for (int at = 0; at < queries.size(); at++) {
            if (OsvQuery.covers(queries.get(at).ecosystem())) {
                asked.add(at);
            }
        }
        Map<String, JsonNode> fetched = new HashMap<>();
        for (int from = 0; from < asked.size(); from += OsvQuery.BATCH_LIMIT) {
            List<Integer> chunk = asked.subList(from, Math.min(asked.size(), from + OsvQuery.BATCH_LIMIT));
            List<AdvisorySource.Query> batch = chunk.stream().map(queries::get).toList();
            List<OsvQuery.Listed> listed;
            try {
                listed = OsvQuery.ask(client, querybatch, batch);
            } catch (RuntimeException failed) {
                batch.forEach(query -> fetches.failed(query.ecosystem() + '|' + query.coordinate() + '|'
                        + query.version()));
                throw failed;
            }
            for (int i = 0; i < chunk.size(); i++) {
                AdvisorySource.Query query = batch.get(i);
                if (listed.get(i).more()) {
                    // More records than a batch answer carries: the query's own pages are drawn instead.
                    answers.set(chunk.get(i), advisories(query.ecosystem(), query.coordinate(), query.version()));
                    continue;
                }
                List<Advisory> found = new ArrayList<>();
                for (OsvQuery.Record named : listed.get(i).records()) {
                    if (named.id().startsWith("MAL-")) {
                        malicious(record(fetched, named, query), query.coordinate()).ifPresent(found::add);
                    }
                }
                fetches.fetched(query.ecosystem() + '|' + query.coordinate() + '|' + query.version());
                answers.set(chunk.get(i), List.copyOf(found));
            }
        }
        return List.copyOf(answers);
    }

    /** The record a batch named, fetched once per batch; a failed fetch raises, so the batch fails closed. */
    private JsonNode record(Map<String, JsonNode> fetched, OsvQuery.Record named, AdvisorySource.Query query) {
        String key = named.id() + ' ' + named.modified();
        JsonNode record = fetched.get(key);
        if (record == null) {
            try {
                record = OsvQuery.fetch(client, vulns, named.id());
            } catch (IOException e) {
                fetches.failed(query.ecosystem() + '|' + query.coordinate() + '|' + query.version());
                throw new UncheckedIOException("Failed to fetch the malicious-package record " + named.id()
                        + " for " + query.ecosystem() + " " + query.coordinate() + " " + query.version(), e);
            }
            fetched.put(key, record);
        }
        return record;
    }

    /** The ecosystems OSV serves the curated dataset in. */
    @Override
    public Set<String> ecosystems() {
        return OsvQuery.covered();
    }

    /** Display-only for this feed, which fails closed, but derived per coordinate as every feed's is: while a
     *  coordinate this feed could not screen is inside its retry window the reading is not authoritative. It clears
     *  when that coordinate screens again or its window lapses; another coordinate answering says nothing about it. */
    @Override
    public Freshness freshness() {
        return fetches.freshness();
    }

    /** The {@link Endpoint} seam as a transport: a recorded body answered as a 200, which the client bounds, screens
     *  and paginates as it does a live response. */
    private static FeedTransport transport(Endpoint endpoint) {
        return (request, timeout) -> FeedResponse.of(200, endpoint.query(request.body()));
    }

    /** The advisory a record makes: one of the dataset's own {@code MAL-} records, and nothing for any other OSV
     *  answers beside it. */
    private static Optional<Advisory> malicious(JsonNode vuln, String coordinate) {
        String id = vuln.path("id").asString(null);
        if (id == null || !id.startsWith("MAL-")) {
            return Optional.empty();
        }
        return Optional.of(new Advisory(id, severityOf(vuln), true, Osv.fixedVersions(vuln, coordinate),
                cvesOf(vuln), descriptionOf(vuln), aliasesOf(vuln)));
    }

    // The record's one-line summary, falling back to a bounded prefix of the long-form details - carried so the
    // findings ledger persists what the record says without a display surface re-fetching the feed.
    private static String descriptionOf(JsonNode vuln) {
        return Advisory.description(vuln.path("summary").asString(null), vuln.path("details").asString(""));
    }

    // The reviewer's severity word when the record carries one; an unworded record stays NONE rather than inventing
    // a score, since the gate acts on the malicious flag.
    private static Severity severityOf(JsonNode vuln) {
        return Severity.ofWord(vuln.path("database_specific").path("severity").asString(null), Severity.NONE);
    }

    // Every alias the record names, whatever its namespace - what combined() merges two feeds' records of one flaw on.
    private static List<String> aliasesOf(JsonNode vuln) {
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : vuln.path("aliases")) {
            String value = alias.asString(null);
            if (value != null && !value.isBlank() && !aliases.contains(value)) {
                aliases.add(value);
            }
        }
        return aliases;
    }

    // The advisory's CVE aliases, the keys the known-exploited catalogue uses.
    private static List<String> cvesOf(JsonNode vuln) {
        List<String> cves = new ArrayList<>();
        for (JsonNode alias : vuln.path("aliases")) {
            String value = alias.asString(null);
            if (value != null && value.startsWith("CVE-") && !cves.contains(value)) {
                cves.add(value);
            }
        }
        return cves;
    }
}
