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
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.feed.Osv;
import build.jenesis.repository.compliance.osv.OsvQuery;

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
 * {@code FeedCache}, so every lookup reaches the feed.
 */
public final class OpenSsfMaliciousSource implements AdvisorySource {

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

    private final FeedClient client;
    private final URI query;
    private final FreshnessTracker fetches;

    public OpenSsfMaliciousSource() {
        this(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC());
    }

    public OpenSsfMaliciousSource(Endpoint endpoint) {
        this(FeedClient.of(FEED, transport(endpoint), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC());
    }

    private OpenSsfMaliciousSource(FeedClient client, URI base, Clock clock) {
        this.client = client;
        this.query = base.resolve("/v1/query");
        this.fetches = new FreshnessTracker(clock);
    }

    /** The production form, over the deployment clock the reading's retry window is measured on. */
    public static OpenSsfMaliciousSource over(URI base, Clock clock) {
        return new OpenSsfMaliciousSource(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), base, clock);
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        String key = ecosystem + '|' + coordinate + '|' + version;
        try {
            List<Advisory> advisories = client.fetch(OsvQuery.request(query, ecosystem, coordinate, version, null),
                    () -> new OsvQuery.Pages(query, ecosystem, coordinate, version,
                            vuln -> malicious(vuln, coordinate))).orElse(List.of());
            fetches.fetched(key);
            return advisories;
        } catch (FeedException e) {
            fetches.failed(key);
            throw new UncheckedIOException("Failed to query the malicious-package feed for "
                    + ecosystem + " " + coordinate + " " + version + " (" + OsvQuery.reason(e) + ")", e);
        }
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
                cvesOf(vuln), descriptionOf(vuln)));
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

    // The advisory's CVE aliases, the keys the known-exploited catalogue uses and combined() de-duplicates by.
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
