package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What OSV changed, drawn from its export's per-ecosystem change lists into the feed's log: a first draw only records
 * where each list stands, a later one names the packages the records changed since affect - in the product's
 * ecosystem names, a release-qualified ecosystem by its base - and a list whose position lies past the window a draw
 * reads is a gap rather than a shorter list. An ecosystem the export holds no list for fails nothing, and every record
 * of the list it gains later is drawn as a change.
 */
class OsvChangesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path root;

    private ArtifactStore space;
    private final Map<String, String> lists = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();
    /** The ecosystems the export holds no list for, which it answers 404. */
    private final Set<String> absent = new HashSet<>();

    @BeforeEach
    void setUp() {
        space = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("signals").scope("osv");
    }

    private OsvAdvisorySource source() {
        return OsvAdvisorySource.responding(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/modified_id.csv")) {
                String ecosystem = URLDecoder.decode(path.substring(1, path.indexOf('/', 1)), StandardCharsets.UTF_8);
                return absent.contains(ecosystem) ? FeedResponse.of(404, "")
                        : FeedResponse.of(200, lists.getOrDefault(ecosystem, ""));
            }
            fetched.add(path);
            return FeedResponse.of(200, switch (path) {
                case "/v1/vulns/GHSA-aaaa" -> record("GHSA-aaaa", "[\"Maven\",\"org.acme:a\"]");
                case "/v1/vulns/GHSA-bbbb" -> record("GHSA-bbbb",
                        "[\"Maven\",\"org.acme:b\"],[\"Debian:12\",\"openssl\"],[\"NotOurs\",\"thing\"]");
                default -> {
                    if (!path.startsWith("/v1/vulns/GHSA-gen-")) {
                        throw new AssertionError("asked " + request.uri());
                    }
                    String id = path.substring("/v1/vulns/".length());
                    yield record(id, "[\"Maven\",\"org.acme:" + id + "\"]");
                }
            });
        }, () -> space, CLOCK);
    }

    private static String record(String id, String affected) {
        StringBuilder packages = new StringBuilder();
        for (String pair : affected.split("(?<=]),")) {
            String[] parts = pair.substring(1, pair.length() - 1).split(",");
            if (!packages.isEmpty()) {
                packages.append(',');
            }
            packages.append("{\"package\":{\"ecosystem\":").append(parts[0]).append(",\"name\":").append(parts[1])
                    .append("}}");
        }
        return "{\"id\":\"" + id + "\",\"affected\":[" + packages + "]}";
    }

    @Test
    void a_draw_stops_at_its_budget_but_names_the_instant_it_stopped_at_whole() throws IOException {
        lists.put("Maven", "2026-09-01T00:00:00Z,GHSA-old\n");
        source().drawChanges();
        // Oldest first: 499 records a minute apart, two more sharing the instant the budget runs out at, and one
        // after - so the budget is met inside an instant the next draw, whose position is exclusive, would skip.
        Instant start = Instant.parse("2026-09-02T00:00:00Z");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 502; i++) {
            Instant modified = start.plus(Duration.ofMinutes(i < 499 ? i : i < 501 ? 499 : 600));
            lines.addFirst(modified + ",GHSA-gen-" + i);
        }
        lists.put("Maven", String.join("\n", lines) + "\n2026-09-01T00:00:00Z,GHSA-old\n");

        source().drawChanges();
        assertThat(fetched).as("the budget, and the second record at the instant it ran out at").hasSize(501)
                .contains("/v1/vulns/GHSA-gen-500").doesNotContain("/v1/vulns/GHSA-gen-501");

        fetched.clear();
        source().drawChanges();
        assertThat(fetched).as("the next draw resumes after that instant").containsExactly("/v1/vulns/GHSA-gen-501");
    }

    @Test
    void a_first_draw_records_where_the_lists_stand_and_names_nothing() throws IOException {
        lists.put("Maven", "2026-10-01T00:00:00Z,GHSA-old\n2026-09-30T00:00:00Z,GHSA-older\n");

        assertThat(source().drawChanges()).isZero();
        assertThat(fetched).as("no record is fetched to learn where a list stands").isEmpty();
        assertThat(source().changes(0)).satisfies(log -> {
            assertThat(log.packages()).isEmpty();
            assertThat(log.gap()).isFalse();
        });
    }

    @Test
    void a_later_draw_names_the_packages_the_records_changed_since_affect() throws IOException {
        lists.put("Maven", "2026-10-01T00:00:00Z,GHSA-old\n");
        source().drawChanges();
        lists.put("Maven", "2026-10-03T00:00:00Z,GHSA-bbbb\n2026-10-02T00:00:00Z,GHSA-aaaa\n"
                + "2026-10-01T00:00:00Z,GHSA-old\n");

        assertThat(source().drawChanges()).isEqualTo(3);

        AdvisorySource.ChangeLog log = source().changes(0);
        assertThat(log.packages()).containsExactlyInAnyOrder(
                new AdvisorySource.Package("Maven", "org.acme:a"),
                new AdvisorySource.Package("Maven", "org.acme:b"),
                new AdvisorySource.Package("Debian", "openssl"));
        assertThat(log.gap()).isFalse();
        assertThat(fetched).as("only what changed past the position, and nothing at it")
                .containsExactlyInAnyOrder("/v1/vulns/GHSA-aaaa", "/v1/vulns/GHSA-bbbb");
        assertThat(source().changes(log.latest()).packages()).as("a reader at the latest has nothing new").isEmpty();

        fetched.clear();
        assertThat(source().drawChanges()).as("a draw with nothing new since names nothing").isZero();
        assertThat(fetched).isEmpty();
    }

    @Test
    void an_ecosystem_the_export_lists_nothing_for_yet_has_every_record_it_gains_drawn() throws IOException {
        absent.add("ConanCenter");

        assertThat(source().drawChanges()).as("no list is not a failure").isZero();

        absent.clear();
        lists.put("ConanCenter", "2026-10-03T00:00:00Z,GHSA-aaaa\n");
        assertThat(source().drawChanges()).as("the first record of a new list is a change, not where it stands")
                .isEqualTo(1);
        assertThat(source().changes(0).packages()).containsExactly(new AdvisorySource.Package("Maven", "org.acme:a"));
    }

    @Test
    void a_list_whose_position_lies_past_the_window_is_a_gap() throws IOException {
        lists.put("PyPI", "2026-10-01T00:00:00Z,PYSEC-old\n");
        source().drawChanges();
        StringBuilder flood = new StringBuilder();
        Instant at = Instant.parse("2026-10-04T00:00:00Z");
        while (flood.length() <= (1 << 20)) {
            flood.append(at).append(",PYSEC-").append(flood.length()).append('\n');
            at = at.minusMillis(1);
        }
        lists.put("PyPI", flood + "2026-10-01T00:00:00Z,PYSEC-old\n");

        source().drawChanges();

        assertThat(source().changes(0).gap()).as("more changed than one draw reads is not fewer changed").isTrue();
        assertThat(fetched).as("and nothing in the window is fetched one by one").isEmpty();
    }
}
