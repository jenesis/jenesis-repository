package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;

/**
 * A query of the OSV.dev API for one package version, for every source that asks it - the vulnerability feed and the
 * curated malicious-packages dataset it serves alike: the request body, the ecosystem in OSV's spelling, and the walk of
 * {@code next_page_token} across the answer's pages - and the batch form a source asking about many versions at once
 * takes: {@code /v1/querybatch}, which lists each version's records by id, and the fetch of a record in full. What a
 * source keeps of each record is its own.
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

    /** The ecosystems OSV also publishes per distribution release, as {@code <name>:<release>} ({@code Alpine:v3.19},
     *  {@code Debian:12}): a query naming one asks that release. Alpine's advisories are published only that way. */
    private static final Set<String> RELEASED = Set.of("Alpine", Ecosystems.DEBIAN);

    private OsvQuery() {
    }

    /** Whether OSV publishes {@code ecosystem}, so a query of it is answered rather than refused. A source answers
     *  no advisories for an ecosystem it does not cover. */
    public static boolean covers(String ecosystem) {
        int release = ecosystem.indexOf(':');
        return release < 0 ? OSV_NAMES.containsKey(ecosystem)
                : RELEASED.contains(ecosystem.substring(0, release)) && release < ecosystem.length() - 1;
    }

    /** Every ecosystem name OSV publishes that the product asks it about, in OSV's spelling. */
    public static List<String> osvEcosystems() {
        return OSV_NAMES.values().stream().sorted().toList();
    }

    /** The product's ecosystem OSV's {@code osvName} is, or empty for one the product does not ask about. */
    public static Optional<String> ecosystem(String osvName) {
        return OSV_NAMES.entrySet().stream().filter(entry -> entry.getValue().equals(osvName))
                .map(Map.Entry::getKey).findFirst();
    }

    /** Every ecosystem OSV publishes that the product asks it about, in the product's spelling. */
    public static Set<String> covered() {
        return OSV_NAMES.keySet();
    }

    /** The name OSV knows the product's {@code ecosystem} by, which is the one a query carries: a release-qualified
     *  one keeps its release. */
    private static String name(String ecosystem) {
        if (!covers(ecosystem)) {
            throw new IllegalArgumentException("OSV publishes no ecosystem named " + ecosystem);
        }
        int release = ecosystem.indexOf(':');
        return release < 0 ? OSV_NAMES.get(ecosystem)
                : OSV_NAMES.get(ecosystem.substring(0, release)) + ecosystem.substring(release);
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

    /** The most queries OSV takes in one {@code /v1/querybatch}; one more is refused. */
    public static final int BATCH_LIMIT = 1_000;

    /** One {@code /v1/querybatch} request for {@code queries}, at most {@link #BATCH_LIMIT}, each an ecosystem
     *  {@link #covers covered}. OSV answers each with the ids and modification instants of the records affecting it. */
    public static FeedRequest batch(URI querybatch, List<AdvisorySource.Query> queries) {
        if (queries.size() > BATCH_LIMIT) {
            throw new IllegalArgumentException("OSV takes at most " + BATCH_LIMIT + " queries in one batch, not "
                    + queries.size());
        }
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (AdvisorySource.Query query : queries) {
            bodies.add(Map.of("version", query.version(),
                    "package", Map.of("ecosystem", name(query.ecosystem()), "name", query.coordinate())));
        }
        return FeedRequest.post(querybatch, JSON.writeValueAsString(Map.of("queries", bodies)), "application/json");
    }

    /** One query's answer in a batch: the records affecting it, each named by id and last modification, and whether
     *  OSV holds more than it answered here - a query to ask on its own. */
    public record Listed(List<Record> records, boolean more) {
    }

    /** A record a batch names: its id and the instant it was last modified, which keys a fetched copy of it. */
    public record Record(String id, String modified) {
    }

    /** The batch answer {@code body}, one {@link Listed} per query in their order; a body answering a different
     *  number of queries than were asked is refused, since a position would then name another query's records. */
    public static List<Listed> listed(JsonNode body, int asked) throws IOException {
        JsonNode results = body.path("results");
        if (!results.isArray() || results.size() != asked) {
            throw new IOException("OSV answered " + (results.isArray() ? results.size() : "no") + " results to a "
                    + "batch of " + asked + " queries");
        }
        List<Listed> listed = new ArrayList<>();
        for (JsonNode result : results) {
            List<Record> records = new ArrayList<>();
            for (JsonNode vuln : result.path("vulns")) {
                String id = vuln.path("id").asString(null);
                if (id != null && !id.isBlank()) {
                    records.add(new Record(id, vuln.path("modified").asString("")));
                }
            }
            String token = result.path("next_page_token").asString(null);
            listed.add(new Listed(List.copyOf(records), token != null && !token.isBlank()));
        }
        return List.copyOf(listed);
    }

    /** The request for one record in full, by its {@code id}. */
    public static FeedRequest record(URI vulns, String id) {
        return FeedRequest.get(vulns.resolve(URLEncoder.encode(id, StandardCharsets.UTF_8)));
    }

    /** One {@code /v1/querybatch} exchange through {@code client}: what OSV lists for each of {@code queries}, in
     *  their order. A failed exchange raises, so a batched source fails closed as a single query does. */
    public static List<Listed> ask(FeedClient client, URI querybatch, List<AdvisorySource.Query> queries) {
        try {
            JsonNode body = client.fetch(batch(querybatch, queries), FeedClient.Reader.document(JSON::readTree))
                    .value().orElseThrow();
            return listed(body, queries.size());
        } catch (FeedException e) {
            throw new UncheckedIOException("Failed to query " + client.feed() + " for a batch of " + queries.size()
                    + " versions (" + reason(e) + ")", new IOException(e));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + client.feed() + "'s answer to a batch of "
                    + queries.size() + " versions", e);
        }
    }

    /** One record in full through {@code client}, by its {@code id}; an answer naming another record is refused. */
    public static JsonNode fetch(FeedClient client, URI vulns, String id) throws IOException {
        try {
            JsonNode record = client.fetch(record(vulns, id), FeedClient.Reader.document(JSON::readTree))
                    .value().orElseThrow();
            if (!id.equals(record.path("id").asString(null))) {
                throw new IOException("OSV answered a request for record " + id + " with another record");
            }
            return record;
        } catch (FeedException e) {
            throw new IOException(reason(e), e);
        }
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
    /** How long OSV's answer to one query is shared with another feed asking the same: long enough for the feeds one
     *  screen asks in turn, short enough that it is no cache of record - each feed keeps its own. */
    public static final Duration SHARED_FOR = Duration.ofSeconds(10);

    /**
     * OSV's answers shared between the feeds that ask one endpoint the same question: the vulnerability feed and the
     * malicious-package feed ask OSV the same thing about a copy one screen judges, and each maps the one answer its
     * own way, so the copy costs OSV one query rather than two. An answer is shared for {@link #SHARED_FOR}; a failure
     * is the asker's to report and is never shared, and a feed its client skips shares nothing.
     *
     * <p>The production feeds share {@link #node()}, the node's; a source built for a test answers through its own
     * exchange and shares nothing unless the test hands two sources one {@link #between()}.
     */
    public static final class Shared {

        private static final Shared NODE = new Shared(true);

        /** The most answers remembered at once; the oldest go first. */
        private static final int REMEMBERED = 10_000;

        private final boolean remembers;
        private final Map<String, Remembered> answers = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
                        return size() > REMEMBERED;
                    }
                });

        private Shared(boolean remembers) {
            this.remembers = remembers;
        }

        /** The node's: what the production feeds share. */
        public static Shared node() {
            return NODE;
        }

        /** One remembering nothing, so every ask reaches the feed. */
        public static Shared none() {
            return new Shared(false);
        }

        /** A fresh one, for the sources a test builds to share. */
        public static Shared between() {
            return new Shared(true);
        }

        /** Every vulnerability record OSV answers at {@code query} for {@code coordinate} at {@code version}, every
         *  page drawn through {@code client} - or the answer another feed sharing this drew within
         *  {@link #SHARED_FOR}. */
        public List<JsonNode> answered(FeedClient client, URI query, String ecosystem, String coordinate,
                                       String version) throws FeedException {
            String key = query + " " + ecosystem + " " + coordinate + " " + version;
            Instant now = Instant.now();
            Remembered held = remembers ? answers.get(key) : null;
            if (held != null && held.until().isAfter(now)) {
                return held.vulns();
            }
            FeedClient.Answer<List<JsonNode>> answer = client.fetch(
                    request(query, ecosystem, coordinate, version, null),
                    () -> new Records(query, ecosystem, coordinate, version));
            List<JsonNode> vulns = answer.orElse(List.of());
            if (remembers && answer.fetched()) {
                answers.put(key, new Remembered(vulns, now.plus(SHARED_FOR)));
            }
            return vulns;
        }
    }

    /** One answer and the instant it stops being shared. */
    private record Remembered(List<JsonNode> vulns, Instant until) {
    }

    /** Every page's records of one query, raw. */
    private static final class Records implements FeedClient.Reader<List<JsonNode>> {

        private final URI query;
        private final String ecosystem;
        private final String coordinate;
        private final String version;
        private final List<JsonNode> vulns = new ArrayList<>();

        private Records(URI query, String ecosystem, String coordinate, String version) {
            this.query = query;
            this.ecosystem = ecosystem;
            this.coordinate = coordinate;
            this.version = version;
        }

        @Override
        public Optional<FeedRequest> read(int page, FeedResponse response) throws IOException {
            JsonNode root = JSON.readTree(response.body());
            root.path("vulns").forEach(vulns::add);
            String pageToken = root.path("next_page_token").asString(null);
            return pageToken == null || pageToken.isBlank()
                    ? Optional.empty()
                    : Optional.of(request(query, ecosystem, coordinate, version, pageToken));
        }

        @Override
        public List<JsonNode> complete() {
            return List.copyOf(vulns);
        }
    }
}
