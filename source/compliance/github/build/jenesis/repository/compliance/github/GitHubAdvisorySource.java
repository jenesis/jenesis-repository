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
 * An {@link AdvisorySource} over the GitHub Advisory Database. For each coordinate it queries the global-advisories
 * REST API at the requested version ({@code affects=<name>@<version>}, matched server-side) and maps each advisory to
 * an {@link Advisory}: GHSA id, severity from the CVSS base score (else the severity word), the fixing version, CVE
 * aliases, and whether GitHub classifies it as malware. Ecosystem names go through this feed's
 * {@link Ecosystems.Vocabulary} (GitHub calls PyPI {@code pip}); an untracked ecosystem is never queried. Composed with
 * OSV through {@link AdvisorySource#combined}.
 *
 * <p>Transport is the {@link FeedClient}'s - client and timeouts, whole-fetch deadline, status handling, byte cap,
 * retries, page cap and fail-closed policy. GitHub's own half is the URL, the {@code Bearer} credential, the RFC5988
 * {@code Link: rel="next"} cursor and the field mapping. Every page is followed, so an advisory on page two is never
 * dropped, and a feed that keeps advertising a next page fails at the cap.
 *
 * <p><strong>The cursor may not leave the feed's origin.</strong> {@code rel="next"} is followed verbatim with the
 * operator's Bearer token, so a hostile or substituted answer pointing it elsewhere would steer the fetch (SSRF) and
 * leak the token. GitHub's pagination never leaves its origin, so the client's
 * {@link FeedPolicy#sameOriginOnly() same-origin} rule - scheme, host and port - refuses one; a cursor on the
 * operator's own origin, a private GHE included, paginates.
 *
 * <p>The network operation sits behind an {@link Endpoint} seam, so recorded answers drive the parsing through the same
 * client, caps and pagination as the live query. A failed query throws, so the gate fails closed.
 *
 * <p>Lookups sit behind a {@link FeedCache#failClosed fail-closed} {@link FeedCache}: a version screened again within
 * the window answers from memory, a cold burst on one coordinate is one query, and a failed refresh past the window
 * raises rather than re-serving the old list. The hour-long window also keeps a busy repository inside GitHub's rate
 * limit while a new advisory reaches the gate within the hour.
 */
public final class GitHubAdvisorySource implements AdvisorySource {

    /** How long one coordinate version's answer is served before GitHub is asked again. */
    private static final Duration TTL = Duration.ofHours(1);

    /** The single network operation, isolated so a test answers with a fixed response. A non-null {@code next} is the
     *  absolute {@code Link: rel="next"} URL to fetch, {@code ecosystem} and {@code affects} then only identifying the
     *  query; when null the first page is built from them. */
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

    /** GitHub's ecosystem name per canonical ecosystem; absent where GitHub tracks none (Rust, Packagist, CocoaPods,
     *  Conan, Linux distributions), so those coordinates spend no request. */
    private static final Ecosystems.Vocabulary ECOSYSTEMS = Ecosystems.vocabulary(Map.of(
            Ecosystems.MAVEN, "maven",
            Ecosystems.NPM, "npm",
            Ecosystems.PYPI, "pip",
            Ecosystems.GO, "go",
            Ecosystems.NUGET, "nuget",
            Ecosystems.RUBYGEMS, "rubygems"));

    /** A connect timeout, so a black-holed host fails the fetch rather than parking the gate thread. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A gating feed, so the client's fail-closed defaults unchanged: 30 s per request, a 5 min whole-fetch deadline,
     *  50 pages (5000 advisories at {@code per_page=100}, beyond any real coordinate), 3 attempts with backoff
     *  honouring {@code Retry-After}, a capped body, and a same-origin cursor. */
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

    /** One coordinate version's query, the cache's loader; the key joins the three coordinates by spaces, which none
     *  contains. */
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

    /** Display-only for this fail-closed feed, so no caller misreads a degraded value. It is the cache's own account:
     *  while a coordinate this feed could not screen is in its retry window the reading is not authoritative, clearing
     *  when it screens again or the window lapses. */
    @Override
    public Freshness freshness() {
        return cache.freshness();
    }

    /** The client's failure reason in an operator's words, so a log line says whether the fetch hit the page cap, a
     *  cross-origin cursor or a rejected status. */
    private static String reason(FeedException failure) {
        return failure.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** How this source reaches GitHub for one query: one shared JDK transport live (a client per lookup would leak
     *  selector threads), resolved per fetch because the {@link Endpoint} seam needs the query it answers. */
    @FunctionalInterface
    private interface Transports {
        FeedTransport of(String ecosystem, String affects, URI first);
    }

    /** The {@link Endpoint} seam as a transport: a recorded body answered as a 200 whose {@code Link} header carries
     *  the seam's {@code next}, so the RFC5988 parser, byte cap, page cap and origin guard all run over recorded
     *  answers. */
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

    /** Folds GitHub's pages into the advisory list - a fresh instance per attempt, so a retry never folds a page twice
     *  and a capped fetch drops its partial list. The next page re-points the first request, so the credential travels
     *  with the cursor, which is why a cursor leaving the origin is refused. */
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

    // The absolute URL of an RFC5988 Link header's rel="next" member, or null; GitHub advances only through this opaque
    // URL, so it is followed verbatim.
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

    // The advisory's summary, else a bounded prefix of its description, so the findings ledger keeps what the advisory
    // says without re-fetching the feed.
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

    // The versions fixing this advisory for the queried package: each matching vulnerability's first_patched_version.
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
