package build.jenesis.repository.compliance.github;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.FeedCache;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;

/**
 * An {@link AdvisorySource} backed by the GitHub Advisory Database (github.com/advisories). For each coordinate it
 * queries the global-advisories REST API for the package in the given ecosystem at the requested version - GitHub
 * matches the version server-side through {@code affects=<name>@<version>} - and maps every returned advisory to an
 * {@link Advisory}: its GHSA id, the severity from the advisory's CVSS base score (falling back to the severity word),
 * the version GitHub records as fixing it, its CVE aliases, and whether GitHub classifies it as a malicious package
 * ({@code type: malware}). GitHub's ecosystem names differ from the canonical ones (it calls PyPI {@code pip}), so the
 * name is mapped through this feed's own {@link Ecosystems.Vocabulary}; an ecosystem GitHub does not track (Debian)
 * yields nothing and is never queried. Composed with OSV through {@link AdvisorySource#combined}.
 *
 * <p>The transport half is the {@link FeedClient}'s: the HTTP client and its timeouts, the whole-fetch deadline,
 * the non-200 branch, the response byte cap, the retry schedule, the page cap and the fail-closed policy - including
 * the cursor-origin guard below - are shared with every other feed rather than re-rolled here. What stays is GitHub's
 * own half: the URL shape, the {@code Bearer} credential, the RFC5988 {@code Link: rel="next"} cursor and the field
 * mapping. Every page is followed before the source answers, so an advisory only on page two of a widely-affected
 * package is never silently dropped, and a feed that keeps advertising a next page fails visibly at the cap.
 *
 * <p><strong>The cursor may not leave the feed's origin.</strong> GitHub's {@code rel="next"} is an absolute URL
 * followed verbatim <em>with the operator's Bearer token attached</em>, so a compromised, MITM'd or hostile
 * self-hosted-GHE answer that points {@code next} at any other origin both steers the fetch (an SSRF, when the target
 * is internal) and exfiltrates the token (when it is a public attacker host). GitHub cursor pagination never leaves
 * its own origin, so the client's {@link FeedPolicy#sameOriginOnly() same-origin} rule refuses one - now comparing the
 * scheme and port as well as the host, where this feed compared only the host. A {@code next} on the operator's own
 * feed origin (including a private GHE) is trusted and still paginates.
 *
 * <p>The single network operation still sits behind an {@link Endpoint} seam, so the parsing is driven from recorded
 * answers while the live query stays the default - and a recorded answer travels through the very same client, caps
 * and pagination the live one does. A failed query throws rather than passing, so the gate fails closed.
 *
 * <p>A lookup sits behind the same {@link FeedCache#failClosed fail-closed} {@link FeedCache} every advisory feed uses:
 * the same version screened again inside the window answers without a network call, a cold burst on one coordinate
 * collapses into a single query, and past the window a failed refresh raises rather than re-serving the list already
 * drawn. GitHub rate-limits the unauthenticated form of this API, so the window also keeps a busy repository inside
 * that limit; it is kept to an hour so a newly published advisory reaches the gate within that.
 */
public final class GitHubAdvisorySource implements AdvisorySource {

    /** How long one coordinate version's answer is served before GitHub is asked again. */
    private static final Duration TTL = Duration.ofHours(1);

    /** The single network operation, isolated so a test can answer with a fixed response. {@code next}, when non-null,
     *  is the absolute {@code Link: rel="next"} URL of a subsequent page to fetch directly - {@code ecosystem} and
     *  {@code affects} then only identify the query; when null the first page is built from them. */
    @FunctionalInterface
    public interface Endpoint {
        Page query(String ecosystem, String affects, String next) throws IOException;

        /** One page's body and the absolute URL of the next page ({@code null} when this is the last page). */
        record Page(String body, String next) {
        }
    }

    /** The feed's name - the attribution key its provider answers to and the client names in every failure. */
    private static final String FEED = "github";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.github.com");

    /** GitHub's advisory ecosystem name per canonical ecosystem; an absent one GitHub does not track. GitHub spells
     *  PyPI {@code pip} (where deps.dev spells it {@code pypi}), and tracks no Rust, Packagist, CocoaPods, Conan or
     *  Linux-distribution advisories at all, so those coordinates never spend a request. */
    private static final Ecosystems.Vocabulary ECOSYSTEMS = Ecosystems.vocabulary(Map.of(
            Ecosystems.MAVEN, "maven",
            Ecosystems.NPM, "npm",
            Ecosystems.PYPI, "pip",
            Ecosystems.GO, "go",
            Ecosystems.NUGET, "nuget",
            Ecosystems.RUBYGEMS, "rubygems"));

    /** A bounded connect timeout so a black-holed feed host fails the fetch instead of parking the gate thread that
     *  asked for the lookup. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** GitHub is a gating advisory feed, so it takes the client's fail-closed defaults unchanged: 30 s per request, a
     *  5 min whole-fetch deadline, 50 pages (5000 advisories for one package version at {@code per_page=100}, a
     *  ceiling no real coordinate approaches), 3 attempts with exponential backoff honouring {@code Retry-After}, a
     *  capped response body, and a cursor that may not leave the feed's origin. */
    private static final FeedPolicy POLICY = FeedPolicy.closed();

    private final URI base;
    private final String token;
    private final Transports transports;
    private final FeedCache<List<Advisory>> cache;

    public GitHubAdvisorySource(Endpoint endpoint) {
        this(DEFAULT_ENDPOINT, null, seam(endpoint), Clock.systemUTC());
    }

    private GitHubAdvisorySource(URI base, String token, Transports transports, Clock clock) {
        this.base = base;
        this.token = token;
        this.transports = transports;
        this.cache = FeedCache.failClosed("GitHub advisories", this::query, TTL, clock);
    }

    /** The production form, over the deployment clock the reading's retry window is measured on. */
    public static GitHubAdvisorySource over(URI base, String token, Clock clock) {
        FeedTransport live = FeedTransport.jdk(CONNECT_TIMEOUT);
        return new GitHubAdvisorySource(base, token, (ecosystem, affects, first) -> live, clock);
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        if (ECOSYSTEMS.of(ecosystem) == null) {
            return List.of();                                   // an ecosystem GitHub does not track is never queried
        }
        return cache.get(ecosystem + " " + coordinate + " " + version);
    }

    /** One coordinate version's query, the cache's loader; the key is the three coordinates joined by spaces, which
     *  none of them may contain. */
    private List<Advisory> query(String key) throws IOException {
        int first = key.indexOf(' '), last = key.lastIndexOf(' ');
        String ecosystem = key.substring(0, first), coordinate = key.substring(first + 1, last),
                version = key.substring(last + 1);
        String github = ECOSYSTEMS.of(ecosystem);
        String affects = coordinate + "@" + version;
        FeedRequest query = FeedRequest.get(base.resolve("/advisories?per_page=100&ecosystem=" + github
                        + "&affects=" + URLEncoder.encode(affects, StandardCharsets.UTF_8)))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        FeedRequest request = token == null || token.isBlank() ? query : query.bearer(token);
        try {
            return FeedClient.of(FEED, transports.of(github, affects, request.uri()), POLICY)
                    .fetch(request, () -> new Advisories(request, coordinate))
                    .orElse(List.of());
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

    /** The client's machine-readable failure reason in the operator's words, so a log line says whether the fetch hit
     *  the page cap, a cross-origin cursor or a rejected status without an operator decoding an enum name. */
    private static String reason(FeedException failure) {
        return failure.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** How this source reaches GitHub for one query. The live form is one shared JDK transport (an HTTP client per
     *  lookup would leak a selector thread pool); the {@link Endpoint} seam needs the query it is answering, which is
     *  why this is resolved per fetch rather than held as a plain transport. */
    @FunctionalInterface
    private interface Transports {
        FeedTransport of(String ecosystem, String affects, URI first);
    }

    /** The {@link Endpoint} seam as a transport: a recorded body answered as a 200 whose {@code Link} header carries
     *  the seam's {@code next}, so the production RFC5988 parser, the byte cap, the page cap and the origin guard all
     *  run over a recorded answer exactly as they do over a live one. */
    private static Transports seam(Endpoint endpoint) {
        return (ecosystem, affects, first) -> (request, timeout) -> {
            Endpoint.Page page = endpoint.query(ecosystem, affects,
                    request.uri().equals(first) ? null : request.uri().toString());
            return new FeedResponse(200, link(page.next()),
                    new ByteArrayInputStream(page.body().getBytes(StandardCharsets.UTF_8)));
        };
    }

    private static Map<String, List<String>> link(String next) {
        return next == null || next.isBlank()
                ? Map.of()
                : Map.of("Link", List.of("<" + next + ">; rel=\"next\""));
    }

    /**
     * Folds GitHub's pages into the advisory list. A fresh instance per attempt (the client's contract), so a retry
     * never folds a page twice and a fetch that hits a cap drops its half-filled accumulator instead of answering
     * with it. The next page is drawn by re-pointing the <em>first</em> request, so the credential header travels
     * with the cursor - which is exactly why the client refuses a cursor that left the origin.
     */
    private static final class Advisories implements FeedClient.Reader<List<Advisory>> {

        private final FeedRequest first;
        private final String coordinate;
        private final List<Advisory> advisories = new ArrayList<>();

        private Advisories(FeedRequest first, String coordinate) {
            this.first = first;
            this.coordinate = coordinate;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            for (JsonNode advisory : JSON.readTree(response.body())) {
                String id = advisory.path("ghsa_id").asString(advisory.path("cve_id").asString(null));
                if (id != null) {
                    advisories.add(new Advisory(id, severityOf(advisory),
                            "malware".equals(advisory.path("type").asString(null)),
                            fixedOf(advisory, coordinate), cvesOf(advisory), descriptionOf(advisory)));
                }
            }
            String next = nextLink(response.header("Link").orElse(null));
            return next == null || next.isBlank() ? Optional.empty() : Optional.of(first.to(URI.create(next)));
        }

        @Override
        public List<Advisory> complete() {
            return List.copyOf(advisories);
        }
    }

    // The absolute URL of the rel="next" member of an RFC5988 Link header, or null when none is named. GitHub's cursor
    // pagination advances only through this opaque URL, so it is followed verbatim rather than reconstructed.
    private static String nextLink(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(",")) {
            int open = part.indexOf('<');
            int close = part.indexOf('>', open + 1);
            if (open >= 0 && close > open && part.substring(close).contains("rel=\"next\"")) {
                return part.substring(open + 1, close);
            }
        }
        return null;
    }

    // The advisory's one-line summary, falling back to a bounded prefix of the long-form description - carried so
    // the findings ledger persists what the advisory says without a display surface re-fetching the feed.
    private static String descriptionOf(JsonNode advisory) {
        return Advisory.description(advisory.path("summary").asString(null),
                advisory.path("description").asString(""));
    }

    private static Severity severityOf(JsonNode advisory) {
        JsonNode score = advisory.path("cvss").path("score");
        if (score.isNumber() && score.asDouble() > 0) {
            return Severity.ofScore(score.asDouble());
        }
        return switch (advisory.path("severity").asString("").toLowerCase(Locale.ROOT)) {
            case "low" -> Severity.LOW;
            case "medium", "moderate" -> Severity.MEDIUM;
            case "high" -> Severity.HIGH;
            case "critical" -> Severity.CRITICAL;
            default -> Severity.NONE;
        };
    }

    // The versions that fix this advisory for the queried package, from each matching vulnerability's
    // first_patched_version (GitHub records one per affected version range).
    private static String fixedOf(JsonNode advisory, String coordinate) {
        List<String> fixed = new ArrayList<>();
        for (JsonNode vulnerability : advisory.path("vulnerabilities")) {
            if (coordinate.equals(vulnerability.path("package").path("name").asString(null))) {
                String version = vulnerability.path("first_patched_version").path("identifier").asString(null);
                if (version != null && !fixed.contains(version)) {
                    fixed.add(version);
                }
            }
        }
        return fixed.isEmpty() ? null : String.join(", ", fixed);
    }

    private static List<String> cvesOf(JsonNode advisory) {
        List<String> cves = new ArrayList<>();
        String cve = advisory.path("cve_id").asString(null);
        if (cve != null && cve.startsWith("CVE-")) {
            cves.add(cve);
        }
        for (JsonNode identifier : advisory.path("identifiers")) {
            String value = identifier.path("value").asString(null);
            if (value != null && value.startsWith("CVE-") && !cves.contains(value)) {
                cves.add(value);
            }
        }
        return cves;
    }
}
