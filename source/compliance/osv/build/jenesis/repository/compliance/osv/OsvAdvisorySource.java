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
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.feed.Osv;

/**
 * An {@link AdvisorySource} backed by OSV (osv.dev). For each coordinate it posts {@code /v1/query} for the package in
 * the given ecosystem at the requested version and maps every returned vulnerability to an {@link Advisory}. Severity
 * is the CVSS base score computed from the advisory's CVSS v3/v2 vector (the precise source), falling back to the
 * GitHub {@code database_specific.severity} word only when no scorable vector is present, and to
 * {@link Severity#UNKNOWN} when neither is (a malicious record without a score stays {@link Severity#NONE}).
 *
 * <p>What is <em>not</em> here is as much of the point as what is. The HTTP client and its timeouts, the non-200
 * branch, the whole-fetch deadline, the response byte cap, the retry schedule, the page cap and the fail-closed
 * policy are the {@link FeedClient}'s, identically for every feed. This module keeps only what is OSV's: the URL,
 * the query body, the {@code next_page_token} cursor, and the field mapping. OSV pages a large result set behind a
 * {@code next_page_token} - the same query re-sent with the token echoed back as {@code page_token} - and the client
 * draws every page before the reader completes, so an advisory that only appears on page two of a widely-affected
 * package is never invisible to the gate; a feed that never stops handing back a token fails visibly at the page cap
 * rather than serving a bounded-but-incomplete view.
 *
 * <p>The single network operation still sits behind an {@link Endpoint} seam, so the response parsing and the scoring
 * are driven from recorded payloads while the live query stays the default - a recorded answer travels through the
 * very same client, caps and pagination the live one does. A failed query throws rather than silently passing, so the
 * gate fails closed.
 *
 * <p>A lookup sits behind the same {@link FeedCache#failClosed fail-closed} {@link FeedCache} every advisory feed uses:
 * the same version screened again inside the window answers without a network call, a cold burst on one coordinate
 * collapses into a single query, and past the window a failed refresh raises rather than re-serving the list already
 * drawn. OSV meters nothing, so the window here buys burst-collapsing and outage isolation rather than quota, and it
 * is kept to an hour so a newly published advisory reaches the gate within that.
 */
public final class OsvAdvisorySource implements AdvisorySource {

    /** How long one coordinate version's answer is served before OSV is asked again. */
    private static final Duration TTL = Duration.ofHours(1);

    /** The single network operation, isolated so a test can answer with a fixed response. The argument is the
     *  request body the feed built (the JSON query, carrying {@code page_token} from the second page on). */
    @FunctionalInterface
    public interface Endpoint {
        String query(String body) throws IOException;
    }

    /** The feed's name - the attribution key its provider answers to and the client names in every failure. */
    private static final String FEED = "osv";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.osv.dev");

    /** A bounded connect timeout so a black-holed feed host (a firewall dropping the SYN, no RST) fails the fetch
     *  instead of parking the gate thread that asked for the lookup forever. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** OSV is a gating advisory feed, so it takes the client's fail-closed defaults unchanged: 30 s per request, a
     *  5 min whole-fetch deadline, 50 pages, 3 attempts with exponential backoff honouring {@code Retry-After}, a
     *  capped response body, and a cursor that may not leave the feed's origin. */
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
        return cache.get(key(ecosystem, coordinate, version));
    }

    /** The cache key: the three coordinates joined by spaces, which none of them may contain, so the key reads back
     *  into a query and reads well in the failure the cache raises. */
    private static String key(String ecosystem, String coordinate, String version) {
        return ecosystem + " " + coordinate + " " + version;
    }

    /** One coordinate version's query, the cache's loader: every page drawn through the shared client. */
    private List<Advisory> query(String key) throws IOException {
        int first = key.indexOf(' '), last = key.lastIndexOf(' ');
        String ecosystem = key.substring(0, first), coordinate = key.substring(first + 1, last),
                version = key.substring(last + 1);
        try {
            return client.fetch(request(ecosystem, coordinate, version, null),
                    () -> new Vulnerabilities(ecosystem, coordinate, version)).orElse(List.of());
        } catch (FeedException e) {
            throw new IOException(reason(e), e);
        }
    }

    /** The reading is display-only for this feed - it fails <em>closed</em>, so an outage raises and no caller is
     *  left with a degraded value to misread - and it is the cache's own account of what it serves, so it cannot
     *  disagree with the answers: while a coordinate this feed could not screen is inside its retry window the reading
     *  is not authoritative, and it clears when that coordinate screens again or its window lapses. */
    @Override
    public Freshness freshness() {
        return cache.freshness();
    }

    /** One page's request: the vendor's query URL and body, with the cursor token echoed back from the second page on. */
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
     * Folds OSV's pages into the advisory list. A fresh instance per attempt (the client's contract), so a retry never
     * folds a page twice and a fetch that hits a cap drops its half-filled accumulator instead of answering with it.
     */
    private final class Vulnerabilities implements FeedClient.Reader<List<Advisory>> {

        private final String ecosystem;
        private final String coordinate;
        private final String version;
        private final List<Advisory> advisories = new ArrayList<>();

        private Vulnerabilities(String ecosystem, String coordinate, String version) {
            this.ecosystem = ecosystem;
            this.coordinate = coordinate;
            this.version = version;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            JsonNode root = JSON.readTree(response.body());
            for (JsonNode vuln : root.path("vulns")) {
                String id = vuln.path("id").asString(null);
                if (id != null) {
                    boolean malicious = id.startsWith("MAL-");
                    advisories.add(new Advisory(id, severityOf(vuln, malicious), malicious,
                            Osv.fixedVersions(vuln, coordinate), cvesOf(vuln, id), descriptionOf(vuln)));
                }
            }
            String pageToken = root.path("next_page_token").asString(null);
            return pageToken == null || pageToken.isBlank()
                    ? Optional.empty()
                    : Optional.of(request(ecosystem, coordinate, version, pageToken));
        }

        @Override
        public List<Advisory> complete() {
            return List.copyOf(advisories);
        }
    }

    // The advisory's one-line summary, falling back to a bounded prefix of the long-form details - carried so the
    // findings ledger persists what the advisory says without a display surface re-fetching the feed.
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

    // The patched versions for this package, read from the matching affected entry's range `fixed` events (OSV
    // records a range per still-maintained branch, so a single advisory can be fixed at more than one version).

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
            return switch (word.toUpperCase(Locale.ROOT)) {
                case "LOW" -> Severity.LOW;
                case "MODERATE", "MEDIUM" -> Severity.MEDIUM;
                case "HIGH" -> Severity.HIGH;
                case "CRITICAL" -> Severity.CRITICAL;
                // A word this does not recognise is not "nothing severe": it is a vendor vocabulary we cannot read.
                default -> Severity.UNKNOWN;
            };
        }
        // A malicious-package record (the OpenSSF MAL- dataset) carries no score by design: its verdict is the
        // malicious flag the gate acts on, and an absent score is not a score this source could not read. Banding it
        // UNKNOWN would outrank CRITICAL and have a severity floor reject what the gate's own rule quarantines.
        if (malicious) {
            return Severity.NONE;
        }
        // The entry carried severity vectors and none scored - a CVSS:4.0 vector is the live case, since the scorer
        // reads v2 and v3 only - or it carried no severity information at all. Either way this source cannot say how
        // severe it is, and saying NONE would be the clean answer clause 4 forbids when the truth is unknown: a
        // reject-at-or-above floor then admits a critical advisory, which is exactly what happened.
        //
        // NONE stays reachable and means what it says. It comes from ofScore(0.0) above - a feed that scored the
        // advisory and scored it zero.
        return Severity.UNKNOWN;
    }

    // CVSS v2 and v3.0/v3.1 are closed-form base-score formulas; v4.0 (table-based) is left unscored and falls
    // through to the GitHub severity word when present.
    private static double cvss(String vector) {
        String trimmed = vector.trim();
        if (trimmed.startsWith("CVSS:3.")) {
            return cvss3(trimmed);
        }
        return trimmed.contains("Au:") ? cvss2(trimmed) : -1.0;
    }

    private static double cvss3(String vector) {
        Map<String, String> metric = metrics(vector);
        boolean changed = "C".equals(metric.get("S"));
        double av = switch (metric.getOrDefault("AV", "")) {
            case "N" -> 0.85;
            case "A" -> 0.62;
            case "L" -> 0.55;
            case "P" -> 0.2;
            default -> -1;
        };
        double ac = switch (metric.getOrDefault("AC", "")) {
            case "L" -> 0.77;
            case "H" -> 0.44;
            default -> -1;
        };
        double pr = switch (metric.getOrDefault("PR", "")) {
            case "N" -> 0.85;
            case "L" -> changed ? 0.68 : 0.62;
            case "H" -> changed ? 0.5 : 0.27;
            default -> -1;
        };
        double ui = switch (metric.getOrDefault("UI", "")) {
            case "N" -> 0.85;
            case "R" -> 0.62;
            default -> -1;
        };
        double c = impact3(metric.getOrDefault("C", ""));
        double i = impact3(metric.getOrDefault("I", ""));
        double a = impact3(metric.getOrDefault("A", ""));
        if (av < 0 || ac < 0 || pr < 0 || ui < 0 || c < 0 || i < 0 || a < 0) {
            return -1.0;
        }
        double iss = 1 - (1 - c) * (1 - i) * (1 - a);
        double impact = changed
                ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15)
                : 6.42 * iss;
        if (impact <= 0) {
            return 0.0;
        }
        double exploitability = 8.22 * av * ac * pr * ui;
        return roundUp(Math.min((changed ? 1.08 : 1.0) * (impact + exploitability), 10));
    }

    private static double impact3(String value) {
        return switch (value) {
            case "H" -> 0.56;
            case "L" -> 0.22;
            case "N" -> 0.0;
            default -> -1;
        };
    }

    private static double cvss2(String vector) {
        Map<String, String> metric = metrics(vector);
        double av = switch (metric.getOrDefault("AV", "")) {
            case "L" -> 0.395;
            case "A" -> 0.646;
            case "N" -> 1.0;
            default -> -1;
        };
        double ac = switch (metric.getOrDefault("AC", "")) {
            case "H" -> 0.35;
            case "M" -> 0.61;
            case "L" -> 0.71;
            default -> -1;
        };
        double au = switch (metric.getOrDefault("Au", "")) {
            case "M" -> 0.45;
            case "S" -> 0.56;
            case "N" -> 0.704;
            default -> -1;
        };
        double c = impact2(metric.getOrDefault("C", ""));
        double i = impact2(metric.getOrDefault("I", ""));
        double a = impact2(metric.getOrDefault("A", ""));
        if (av < 0 || ac < 0 || au < 0 || c < 0 || i < 0 || a < 0) {
            return -1.0;
        }
        double impact = 10.41 * (1 - (1 - c) * (1 - i) * (1 - a));
        double exploitability = 20 * av * ac * au;
        double base = ((0.6 * impact) + (0.4 * exploitability) - 1.5) * (impact == 0 ? 0 : 1.176);
        return Math.round(base * 10.0) / 10.0;
    }

    private static double impact2(String value) {
        return switch (value) {
            case "N" -> 0.0;
            case "P" -> 0.275;
            case "C" -> 0.660;
            default -> -1;
        };
    }

    private static double roundUp(double input) {
        long scaled = Math.round(input * 100_000);
        return scaled % 10_000 == 0 ? scaled / 100_000.0 : (Math.floorDiv(scaled, 10_000) + 1) / 10.0;
    }

    private static Map<String, String> metrics(String vector) {
        Map<String, String> metric = new HashMap<>();
        for (String part : vector.split("/")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                metric.put(part.substring(0, colon), part.substring(colon + 1));
            }
        }
        return metric;
    }
}
