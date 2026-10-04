package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;

/**
 * A query of the OSV.dev API for one package version, for every source that asks it - the vulnerability feed and the
 * curated malicious-packages dataset it serves alike: the request body, the ecosystem in OSV's spelling, and the walk of
 * {@code next_page_token} across the answer's pages. What a source keeps of each record is its own.
 */
public final class OsvQuery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The product's ecosystem names OSV publishes, each in OSV's spelling: Conan's recipes are OSV's
     *  {@code ConanCenter}, the rest its own names. OSV refuses a query naming an ecosystem outside its schema with a
     *  {@code 400}, which a source failing closed would read as an outage on every coordinate of it, so a name not
     *  listed - CocoaPods, conda, RPM, Hugging Face and the formats no vulnerability database names - is never asked. */
    private static final Map<String, String> OSV_NAMES = Map.ofEntries(
            Map.entry(Ecosystems.MAVEN, "Maven"),
            Map.entry(Ecosystems.NPM, "npm"),
            Map.entry(Ecosystems.PYPI, "PyPI"),
            Map.entry(Ecosystems.GO, "Go"),
            Map.entry(Ecosystems.NUGET, "NuGet"),
            Map.entry(Ecosystems.RUBYGEMS, "RubyGems"),
            Map.entry(Ecosystems.CRATES_IO, "crates.io"),
            Map.entry(Ecosystems.PACKAGIST, "Packagist"),
            Map.entry(Ecosystems.CONAN, "ConanCenter"),
            Map.entry(Ecosystems.DEBIAN, "Debian"),
            Map.entry("Alpine", "Alpine"),
            Map.entry("Homebrew", "Homebrew"));

    private OsvQuery() {
    }

    /** Whether OSV publishes {@code ecosystem}, so a query of it is answered rather than refused. A source answers
     *  no advisories for an ecosystem it does not cover. */
    public static boolean covers(String ecosystem) {
        return OSV_NAMES.containsKey(ecosystem);
    }

    /** The name OSV knows the product's {@code ecosystem} by, which is the one a query carries. */
    private static String name(String ecosystem) {
        String name = OSV_NAMES.get(ecosystem);
        if (name == null) {
            throw new IllegalArgumentException("OSV publishes no ecosystem named " + ecosystem);
        }
        return name;
    }

    /** One page's request to {@code query} for {@code coordinate} at {@code version}, with the cursor token OSV handed
     *  back for every page after the first. */
    public static FeedRequest request(URI query, String ecosystem, String coordinate, String version,
                                      String pageToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("package", Map.of("ecosystem", name(ecosystem), "name", coordinate));
        if (pageToken != null) {
            body.put("page_token", pageToken);
        }
        return FeedRequest.post(query, JSON.writeValueAsString(body), "application/json");
    }

    /** The client's failure reason in an operator's words, so a log line says whether the fetch hit the page cap, the
     *  deadline or a rejected status. */
    public static String reason(FeedException failure) {
        return failure.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Folds a query's pages into advisories, asking for the next page while OSV hands back a token - a fresh instance
     * per attempt (the client's contract), so a retry never folds a page twice and a capped fetch drops its partial
     * list. {@code keep} maps one record to the advisory a source keeps of it, or to none.
     */
    public static final class Pages implements FeedClient.Reader<List<Advisory>> {

        private final URI query;
        private final String ecosystem;
        private final String coordinate;
        private final String version;
        private final Function<JsonNode, Optional<Advisory>> keep;
        private final List<Advisory> advisories = new ArrayList<>();

        public Pages(URI query, String ecosystem, String coordinate, String version,
                     Function<JsonNode, Optional<Advisory>> keep) {
            this.query = query;
            this.ecosystem = ecosystem;
            this.coordinate = coordinate;
            this.version = version;
            this.keep = keep;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            JsonNode root = JSON.readTree(response.body());
            for (JsonNode vuln : root.path("vulns")) {
                keep.apply(vuln).ifPresent(advisories::add);
            }
            String pageToken = root.path("next_page_token").asString(null);
            return pageToken == null || pageToken.isBlank()
                    ? Optional.empty()
                    : Optional.of(request(query, ecosystem, coordinate, version, pageToken));
        }

        @Override
        public List<Advisory> complete() {
            return List.copyOf(advisories);
        }
    }
}
