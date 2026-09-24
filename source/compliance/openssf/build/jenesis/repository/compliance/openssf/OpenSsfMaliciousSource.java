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

/**
 * An {@link AdvisorySource} over the curated OpenSSF malicious-packages dataset (github.com/ossf/malicious-packages,
 * Apache-2.0), consumed through the OSV.dev API that serves it. For each coordinate it queries {@code /v1/query} for
 * the package in the given ecosystem at the requested version and keeps only the dataset's own records - the
 * {@code MAL-} identifiers - mapping each to a malicious {@link Advisory} with its CVE aliases and the fixed version
 * where the record ends a compromised range. A curated malicious-package record carries no usable CVSS score, so the
 * severity is the reviewer's {@code database_specific.severity} word when present and {@link Severity#NONE} otherwise -
 * the gate acts on the malicious flag, not the severity threshold. Enabled next to the full {@code osv} feed the same
 * {@code MAL-} advisory is never double-counted ({@code AdvisorySource.combined} de-duplicates by id), so this module
 * lets a deployment screen for curated malware without adopting the whole vulnerability feed.
 *
 * <p>The transport half is the {@link FeedClient}'s: the HTTP client and its timeouts, the whole-fetch deadline,
 * the non-200 branch, the response byte cap (this feed previously read an unbounded {@code ofString()} body), the
 * retry schedule honouring {@code Retry-After}, the page cap and the fail-closed policy are shared with every other
 * feed rather than re-rolled here. What stays is the dataset's own half: the query URL, the request body, the
 * {@code next_page_token} cursor and the field mapping. The dataset pages a large result set behind that token - the
 * same query re-sent with the token echoed back as {@code page_token} - and the client draws every page before the
 * reader completes, so a curated record that only appears on page two of a widely-affected package is never invisible
 * to the gate; a feed that never stops handing back a token fails visibly at the page cap rather than serving a
 * bounded-but-incomplete view.
 *
 * <p>The single network operation still sits behind an {@link Endpoint} seam, so the parsing is driven from recorded
 * payloads while the live query stays the default - and a recorded answer travels through the very same client, caps
 * and pagination a live one does. A failed query throws rather than silently passing, so the gate fails closed.
 *
 * <p>Unlike OSV and the GitHub Advisory Database this source holds no {@code FeedCache}: the gate's documented
 * "warm cache read" therefore does not apply to it. Adding one is a caching decision, not a transport one.
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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.osv.dev");

    /** A bounded connect timeout so a black-holed feed host (a firewall dropping the SYN, no RST) fails the fetch
     *  instead of parking the gate thread that asked for the lookup. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A malicious-package feed gates, so it takes the client's fail-closed defaults unchanged: 30 s per request, a
     *  5 min whole-fetch deadline, 50 pages, 3 attempts with exponential backoff honouring {@code Retry-After}, a
     *  capped response body, and a cursor that may not leave the feed's origin. */
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
            List<Advisory> advisories = client.fetch(request(ecosystem, coordinate, version, null),
                    () -> new Malicious(ecosystem, coordinate, version)).orElse(List.of());
            fetches.fetched(key);
            return advisories;
        } catch (FeedException e) {
            fetches.failed(key);
            throw new UncheckedIOException("Failed to query the malicious-package feed for "
                    + ecosystem + " " + coordinate + " " + version + " (" + reason(e) + ")", e);
        }
    }

    /** The reading is display-only for this feed - it fails <em>closed</em>, so an outage raises rather than
     *  answering with a degraded value - but it is derived per coordinate exactly as every other feed's is
     *  (&sect;13): while a coordinate this feed could not screen is inside its retry window the reading is not
     *  authoritative, so a console can no longer read "authoritative, last consulted three days ago" over a feed
     *  whose every lookup has failed since. It clears when that coordinate screens again or its window lapses; a
     *  different coordinate answering says nothing about it. */
    @Override
    public Freshness freshness() {
        return fetches.freshness();
    }

    /** One page's request: the dataset's query URL and body, with the cursor token echoed back from the second page
     *  on. */
    private FeedRequest request(String ecosystem, String coordinate, String version, String pageToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("package", Map.of("ecosystem", ecosystem, "name", coordinate));
        if (pageToken != null) {
            body.put("page_token", pageToken);
        }
        return FeedRequest.post(query, JSON.writeValueAsString(body), "application/json");
    }

    /** The client's machine-readable failure reason in the operator's words, so a log line says whether the fetch hit
     *  the page cap, the deadline or a rejected status without an operator decoding an enum name. */
    private static String reason(FeedException failure) {
        return failure.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The {@link Endpoint} seam as a transport: a recorded body answered as a 200, which the client then bounds,
     *  screens and paginates exactly as it does a live response - so a recorded suite exercises the real fetch. */
    private static FeedTransport transport(Endpoint endpoint) {
        return (request, timeout) -> FeedResponse.of(200, endpoint.query(request.body()));
    }

    /**
     * Folds the dataset's pages into the advisory list. A fresh instance per attempt (the client's contract), so a
     * retry never folds a page twice and a fetch that hits a cap drops its half-filled accumulator instead of
     * answering with it.
     */
    private final class Malicious implements FeedClient.Reader<List<Advisory>> {

        private final String ecosystem;
        private final String coordinate;
        private final String version;
        private final List<Advisory> advisories = new ArrayList<>();

        private Malicious(String ecosystem, String coordinate, String version) {
            this.ecosystem = ecosystem;
            this.coordinate = coordinate;
            this.version = version;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            JsonNode root = JSON.readTree(response.body());
            for (JsonNode vuln : root.path("vulns")) {
                String id = vuln.path("id").asString(null);
                if (id != null && id.startsWith("MAL-")) {
                    advisories.add(new Advisory(id, severityOf(vuln), true, Osv.fixedVersions(vuln, coordinate), cvesOf(vuln),
                            descriptionOf(vuln)));
                }
            }
            String pageToken = root.path("next_page_token").asString(null);
            if (pageToken == null || pageToken.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(request(ecosystem, coordinate, version, pageToken));
        }

        @Override
        public List<Advisory> complete() {
            return List.copyOf(advisories);
        }
    }

    // The record's one-line summary, falling back to a bounded prefix of the long-form details - carried so the
    // findings ledger persists what the record says without a display surface re-fetching the feed.
    private static String descriptionOf(JsonNode vuln) {
        return Advisory.description(vuln.path("summary").asString(null), vuln.path("details").asString(""));
    }

    // The reviewer's severity word when the record carries one; an unworded record stays NONE rather than inventing
    // a score, since the gate acts on the malicious flag.
    private static Severity severityOf(JsonNode vuln) {
        String word = vuln.path("database_specific").path("severity").asString(null);
        if (word == null) {
            return Severity.NONE;
        }
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "LOW" -> Severity.LOW;
            case "MODERATE", "MEDIUM" -> Severity.MEDIUM;
            case "HIGH" -> Severity.HIGH;
            case "CRITICAL" -> Severity.CRITICAL;
            default -> Severity.NONE;
        };
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

    // The version ending a compromised range for this package, read from the matching affected entry's range
    // `fixed` events, where the record carries one (most curated records affect every version and carry none).
}
