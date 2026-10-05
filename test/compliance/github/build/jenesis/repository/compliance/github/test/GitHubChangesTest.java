package build.jenesis.repository.compliance.github.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.github.GitHubAdvisorySource;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What GitHub's advisory database changed, drawn from {@code /advisories} filtered and sorted by {@code updated}: a
 * first draw records where the database stands, a later one names the packages of every advisory updated since, in the
 * product's ecosystem names, following the cursor and resuming from the last advisory's instant.
 */
class GitHubChangesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path root;

    private ArtifactStore space;
    private final List<String> asked = new ArrayList<>();
    private final Map<String, GitHubAdvisorySource.Endpoint.Page> answers = new HashMap<>();

    @BeforeEach
    void setUp() {
        space = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("signals")
                .scope("github");
    }

    private GitHubAdvisorySource source() {
        return GitHubAdvisorySource.exchanging((FeedRequest request) -> {
            String query = URLDecoder.decode(request.uri().getRawQuery(), StandardCharsets.UTF_8);
            asked.add(query);
            return answers.entrySet().stream().filter(entry -> query.contains(entry.getKey())).findFirst()
                    .map(Map.Entry::getValue)
                    .orElseThrow(() -> new AssertionError("asked " + query));
        }, () -> space, CLOCK);
    }

    private static String advisory(String id, String updated, String... packages) {
        StringBuilder vulnerabilities = new StringBuilder();
        for (int i = 0; i < packages.length; i += 2) {
            if (!vulnerabilities.isEmpty()) {
                vulnerabilities.append(',');
            }
            vulnerabilities.append("{\"package\":{\"ecosystem\":\"").append(packages[i]).append("\",\"name\":\"")
                    .append(packages[i + 1]).append("\"}}");
        }
        return "{\"ghsa_id\":\"" + id + "\",\"updated_at\":\"" + updated + "\",\"vulnerabilities\":["
                + vulnerabilities + "]}";
    }

    @Test
    void a_first_draw_records_where_the_database_stands_and_names_nothing() throws IOException {
        answers.put("direction=desc", new GitHubAdvisorySource.Endpoint.Page(
                "[" + advisory("GHSA-newest", "2026-10-04T12:00:00Z", "npm", "left-pad") + "]", null));

        assertThat(source().drawChanges()).isZero();
        assertThat(source().changes(0).packages()).isEmpty();
        assertThat(asked).singleElement().satisfies(query -> assertThat(query).contains("sort=updated"));
    }

    @Test
    void a_later_draw_names_the_packages_of_every_advisory_updated_since() throws IOException {
        answers.put("direction=desc", new GitHubAdvisorySource.Endpoint.Page(
                "[" + advisory("GHSA-newest", "2026-10-04T12:00:00Z", "npm", "left-pad") + "]", null));
        source().drawChanges();
        answers.put("updated=>=2026-10-04T12:00:00+00:00", new GitHubAdvisorySource.Endpoint.Page(
                "[" + advisory("GHSA-aaaa", "2026-10-04T13:00:00Z", "maven", "org.acme:a", "pip", "requests") + "]",
                "https://api.github.com/advisories?after=cursor&page=2"));
        answers.put("after=cursor", new GitHubAdvisorySource.Endpoint.Page(
                "[" + advisory("GHSA-bbbb", "2026-10-04T14:00:00Z", "actions", "acme/checkout", "rust", "serde")
                        + "]", null));

        assertThat(source().drawChanges()).isEqualTo(3);

        AdvisorySource.ChangeLog log = source().changes(0);
        assertThat(log.packages()).as("in the product's names, and none GitHub tracks that the product does not")
                .containsExactlyInAnyOrder(new AdvisorySource.Package("Maven", "org.acme:a"),
                        new AdvisorySource.Package("PyPI", "requests"),
                        new AdvisorySource.Package("crates.io", "serde"));
        assertThat(log.gap()).isFalse();

        answers.put("updated=>=2026-10-04T14:00:00+00:00", new GitHubAdvisorySource.Endpoint.Page("[]", null));
        source().drawChanges();
        assertThat(asked.getLast()).as("the next draw resumes from the last advisory's instant")
                .contains("updated=>=2026-10-04T14:00:00+00:00");
    }
}
