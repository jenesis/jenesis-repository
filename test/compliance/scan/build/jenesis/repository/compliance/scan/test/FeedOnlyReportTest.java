package build.jenesis.repository.compliance.scan.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.closure.spi.Reliance;
import build.jenesis.repository.compliance.scan.VulnerabilityReports;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report a deployment with no findings module renders, recomputed from the live feeds on each read: it asks them
 * about the copies the repository cached from an upstream, and never about a version published here.
 */
class FeedOnlyReportTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void the_live_report_asks_the_feeds_about_cached_copies_only() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("mixed");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record("Maven", "com.acme:built", "1.0", NOW);
        inventory.cache("Maven", "org.public:fetched", "1.0", "https://repo.example/", NOW);
        List<String> asked = new ArrayList<>();
        AdvisorySource feed = new AdvisorySource() {
            @Override
            public Set<String> ecosystems() {
                return Ecosystems.canonical();
            }

            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                asked.add(coordinate);
                return List.of(new Advisory("GHSA-test", Severity.CRITICAL, false, null, List.of()));
            }

            @Override
            public Freshness freshness() {
                return AdvisorySource.none().freshness();
            }
        };

        VulnerabilityReports.VulnerabilityReport report = VulnerabilityReports.read(store, inventory, feed, List.of(),
                Optional.empty(), Reliance.NONE, "", "", null, 50, "vulnerabilities-refresh", List.of());

        assertThat(asked).as("a version published here is asked of no feed").containsExactly("org.public:fetched");
        assertThat(report.vulnerable()).extracting(VulnerabilityReports.VulnerableArtifact::coordinate)
                .containsExactly("org.public:fetched:1.0");
    }

    @Test
    void the_live_report_asks_only_the_feeds_its_repository_selects() throws IOException {
        Map<String, String> settings = new HashMap<>();
        settings.put(AdvisorySource.SELECTION, "quiet");
        ArtifactStore store = ComplianceSettings.bind(ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null), () -> settings::get)
                .scope("default").scope("selecting");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.cache("Maven", "org.public:fetched", "1.0", "https://repo.example/", NOW);
        SequencedMap<String, AdvisorySource> feeds = new LinkedHashMap<>();
        feeds.put("flagging", AdvisorySource.of(Map.of("org.public:fetched",
                List.of(new AdvisorySource.Advisory("GHSA-flagged", Severity.CRITICAL, false, null, List.of())))));
        feeds.put("quiet", AdvisorySource.of(Map.of()));

        VulnerabilityReports.VulnerabilityReport report = VulnerabilityReports.read(store, inventory,
                AdvisorySource.resolve(feeds), List.of(), Optional.empty(), Reliance.NONE, "", "", null, 50,
                "vulnerabilities-refresh", List.of());

        assertThat(report.vulnerable()).as("the flagging feed is the deployment's, not this repository's").isEmpty();
    }
}
