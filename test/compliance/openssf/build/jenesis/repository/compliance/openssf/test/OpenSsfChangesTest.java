package build.jenesis.repository.compliance.openssf.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.openssf.OpenSsfMaliciousSource;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the malicious-package feed changed, drawn from OSV's change lists - its records are OSV's - into a log of its
 * own: a later draw names the packages its {@code MAL-} records changed since affect, and fetches no other record OSV
 * lists, so a feed that publishes changes keeps the full pass a backstop.
 */
class OpenSsfChangesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path root;

    private ArtifactStore space;
    private final Map<String, String> lists = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        space = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("signals")
                .scope("openssf");
    }

    private OpenSsfMaliciousSource source() {
        return OpenSsfMaliciousSource.exchanging(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/modified_id.csv")) {
                String ecosystem = URLDecoder.decode(path.substring(1, path.indexOf('/', 1)), StandardCharsets.UTF_8);
                return lists.getOrDefault(ecosystem, "");
            }
            fetched.add(path);
            return switch (path) {
                case "/v1/vulns/MAL-2026-0001" -> "{\"id\":\"MAL-2026-0001\",\"affected\":[{\"package\":"
                        + "{\"ecosystem\":\"npm\",\"name\":\"evil-package\"}}]}";
                default -> throw new AssertionError("asked " + request.uri());
            };
        }, () -> space, CLOCK);
    }

    @Test
    void a_later_draw_names_what_its_malicious_records_changed_and_fetches_no_other_record() throws IOException {
        lists.put("npm", "2026-10-01T00:00:00Z,GHSA-old\n");
        assertThat(source().drawChanges()).as("a first draw only records where the list stands").isZero();
        lists.put("npm", "2026-10-03T00:00:00Z,MAL-2026-0001\n2026-10-02T00:00:00Z,GHSA-aaaa-bbbb-cccc\n"
                + "2026-10-01T00:00:00Z,GHSA-old\n");

        assertThat(source().drawChanges()).isEqualTo(1);

        assertThat(source().changes(0).packages())
                .containsExactly(new AdvisorySource.Package("npm", "evil-package"));
        assertThat(fetched).as("only the malicious record is fetched; the vulnerability feed's own are not this feed's")
                .containsExactly("/v1/vulns/MAL-2026-0001");
        assertThat(source()).isInstanceOf(AdvisorySource.Changes.class);
    }
}
