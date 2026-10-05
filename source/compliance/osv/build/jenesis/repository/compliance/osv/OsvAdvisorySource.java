package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.FeedCache;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.feed.Osv;
import us.springett.cvss.Cvss;

/**
 * An {@link AdvisorySource} over OSV (osv.dev). For each coordinate it posts {@code /v1/query} for the package at the
 * requested version and maps every vulnerability to an {@link Advisory}. Severity is the CVSS base score computed from
 * the v3 or v2 vector, else GitHub's {@code database_specific.severity} word, else {@link Severity#UNKNOWN} (a
 * malicious record without a score is {@link Severity#NONE}).
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
 */
public final class OsvAdvisorySource implements AdvisorySource {

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

    /** A connect timeout, so a black-holed host (a dropped SYN, no RST) fails the fetch rather than parking the gate
     *  thread. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A gating feed, so the client's fail-closed defaults unchanged: 30 s per request, a 5 min whole-fetch deadline,
     *  50 pages, 3 attempts with backoff honouring {@code Retry-After}, a capped body, and a same-origin cursor. */
    private static final FeedPolicy POLICY = FeedPolicy.closed();

    private final FeedClient client;
    private final URI query;
    private final FeedCache<List<Advisory>> cache;

    public OsvAdvisorySource() {
        this(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC());
    }

    public OsvAdvisorySource(Endpoint endpoint) {
        this(FeedClient.of(FEED, transport(endpoint), POLICY), DEFAULT_ENDPOINT, Clock.systemUTC());
    }

    private OsvAdvisorySource(FeedClient client, URI base, Clock clock) {
        this.client = client;
        this.query = base.resolve("/v1/query");
        this.cache = FeedCache.failClosed("OSV", this::query, TTL, clock);
    }

    /** The production form, over the deployment clock the reading's retry window is measured on. */
    public static OsvAdvisorySource over(URI base, Clock clock) {
        return new OsvAdvisorySource(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), base, clock);
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        if (!OsvQuery.covers(ecosystem)) {
            return List.of();
        }
        return cache.get(key(ecosystem, coordinate, version));
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
            return client.fetch(OsvQuery.request(query, ecosystem, coordinate, version, null),
                    () -> new OsvQuery.Pages(query, ecosystem, coordinate, version, vuln -> advisory(vuln, coordinate)))
                    .orElse(List.of());
        } catch (FeedException e) {
            throw new IOException(OsvQuery.reason(e), e);
        }
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

    /** The advisory a record makes: every record OSV answers, a {@code MAL-} one flagged malicious. */
    private Optional<Advisory> advisory(JsonNode vuln, String coordinate) {
        String id = vuln.path("id").asString(null);
        if (id == null) {
            return Optional.empty();
        }
        boolean malicious = id.startsWith("MAL-");
        return Optional.of(new Advisory(id, severityOf(vuln, malicious), malicious,
                Osv.fixedVersions(vuln, coordinate), cvesOf(vuln, id), descriptionOf(vuln), aliasesOf(vuln)));
    }

    /** Every alias the record names, whatever its namespace - what two feeds' records of one flaw are merged on. */
    static List<String> aliasesOf(JsonNode vuln) {
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : vuln.path("aliases")) {
            String value = alias.asString(null);
            if (value != null && !value.isBlank() && !aliases.contains(value)) {
                aliases.add(value);
            }
        }
        return aliases;
    }

    // The summary, else a bounded prefix of the details, so the findings ledger keeps what the advisory says without
    // re-fetching the feed.
    private static String descriptionOf(JsonNode vuln) {
        return Advisory.description(vuln.path("summary").asString(null), vuln.path("details").asString(""));
    }

    // The advisory's CVE aliases (and its own id when that is a CVE), the keys the known-exploited catalogue uses.
    private static List<String> cvesOf(JsonNode vuln, String id) {
        List<String> cves = new ArrayList<>();
        if (id.startsWith("CVE-")) {
            cves.add(id);
        }
        for (JsonNode alias : vuln.path("aliases")) {
            String value = alias.asString(null);
            if (value != null && value.startsWith("CVE-") && !cves.contains(value)) {
                cves.add(value);
            }
        }
        return cves;
    }

    private static Severity severityOf(JsonNode vuln, boolean malicious) {
        double highest = -1.0;
        for (JsonNode entry : vuln.path("severity")) {
            String vector = entry.path("score").asString(null);
            if (vector != null) {
                highest = Math.max(highest, cvss(vector));
            }
        }
        if (highest >= 0) {
            return Severity.ofScore(highest);
        }
        String word = vuln.path("database_specific").path("severity").asString(null);
        if (word != null) {
            // An unrecognised word is a vocabulary this source cannot read, not "nothing severe".
            return Severity.ofWord(word, Severity.UNKNOWN);
        }
        // A malicious-package record (OpenSSF MAL-) carries no score by design: its verdict is the malicious flag.
        // Banded UNKNOWN it would outrank CRITICAL and a severity floor would reject what the gate's rule quarantines.
        if (malicious) {
            return Severity.NONE;
        }
        // Severity vectors none of which scored (a vector in no CVSS version the scorer reads), or no severity at all:
        // unknown, since NONE would be the clean answer clause 4 forbids and a reject-at-or-above floor would admit a
        // critical advisory. NONE means a feed scored it zero (ofScore(0.0) above).
        return Severity.UNKNOWN;
    }

    // The base score of a CVSS v2, v3.0, v3.1 or v4.0 vector, or -1 for one that does not parse as any of them, which
    // leaves the advisory UNKNOWN rather than scored.
    private static double cvss(String vector) {
        try {
            Cvss parsed = Cvss.fromVector(vector.trim());
            return parsed == null ? -1.0 : parsed.calculateScore().getBaseScore();
        } catch (RuntimeException unreadable) {
            return -1.0;
        }
    }
}
