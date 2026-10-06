package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gateway.ProxyScreen;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied fill is screened by the advisory feeds its repository selects among the deployment's: a feed it does not
 * select is not asked, every one is where it selects none, and one it names that is not on is an outage of the screen
 * rather than a screen by fewer feeds.
 */
class AdvisoryFeedSelectionScreenTest {

    private static final String PATH = "/maven/org/acme/lib/1.0/lib-1.0.pom";

    private static final AdvisorySource FLAGGING = AdvisorySource.of(Map.of("org.acme:lib",
            List.of(new AdvisorySource.Advisory("MAL-2026-0002", Severity.NONE, true))));

    private static final AdvisorySource QUIET = AdvisorySource.of(Map.of());

    @TempDir
    Path root;

    private final Map<String, String> settings = new HashMap<>();

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ComplianceSettings.bind(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null), () -> settings::get);
    }

    @Test
    void a_feed_the_repository_does_not_select_is_not_asked() throws IOException {
        settings.put(AdvisorySource.SELECTION, "quiet");

        assertThat(fetch()).as("the flagging feed is the deployment's, not this repository's").isPresent();
    }

    @Test
    void a_repository_selecting_none_is_screened_by_every_feed() throws IOException {
        assertThat(fetch()).as("the flagging feed holds it").isEmpty();
    }

    @Test
    void a_feed_named_that_is_not_on_is_an_outage_of_the_screen() throws IOException {
        settings.put(AdvisorySource.SELECTION, "quiet,snyk");

        assertThat(fetch()).as("held, rather than screened by fewer feeds than it names").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.rules()).contains(ComplianceGate.FEED_UNAVAILABLE_RULE));
    }

    private Optional<ProxyFormat.Fetched> fetch() throws IOException {
        SequencedMap<String, AdvisorySource> feeds = new LinkedHashMap<>();
        feeds.put("flagging", FLAGGING);
        feeds.put("quiet", QUIET);
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
                AdvisorySource.resolve(feeds)).malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE));
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (_, _) -> Optional.of(new ProxyFormat.Fetched(
                200, "<project><groupId>org.acme</groupId><artifactId>lib</artifactId><version>1.0</version></project>"
                .getBytes(StandardCharsets.UTF_8), Map.of()));
        return new ProxyScreen(gate, store, 0).wrap(upstream, PATH).fetch(URI.create("http://up" + PATH), Map.of());
    }
}
