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

    /** The product's ecosystem names OSV spells otherwise. OSV answers only its schema's ecosystems, and a query in
     *  another spelling gets nothing, which would read as "no advisory". Conan is OSV's {@code ConanCenter}; every
     *  other declared name is OSV's own or one OSV does not publish. */
    private static final Map<String, String> OSV_NAMES = Map.of(Ecosystems.CONAN, "ConanCenter");

    private OsvQuery() {
    }

    /** The name OSV knows the product's {@code ecosystem} by, which is the one a query carries. */
    public static String name(String ecosystem) {
        return OSV_NAMES.getOrDefault(ecosystem, ecosystem);
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
