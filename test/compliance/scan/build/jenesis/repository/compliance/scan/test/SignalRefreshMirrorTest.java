package build.jenesis.repository.compliance.scan.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.scan.SignalRefreshTask;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh pass tells a mirroring feed which ecosystems to keep before it refreshes it: those of the repositories
 * naming it in {@value AdvisorySource#SELECTION}, gathered from every repository the pass visits, and nothing for a
 * repository naming none - so a pass where nothing names it any more asks it to keep nothing.
 */
class SignalRefreshMirrorTest {

    @TempDir
    Path root;

    private ArtifactStore tenant;

    @BeforeEach
    void setUp() throws IOException {
        tenant = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default");
        RepositoryType.create(tenant.scope("releases"), "maven");
        RepositoryType.create(tenant.scope("other"), "maven");
    }

    @Test
    void a_mirror_keeps_the_ecosystems_of_the_repositories_naming_it() throws IOException {
        Mirror mirror = new Mirror();
        SignalRefreshTask task = new SignalRefreshTask(Duration.ofMinutes(5),
                Map.<String, RefreshableSource>of("osv-mirror", mirror));

        task.repository(new Pass(tenant.scope("releases"), "releases", Map.of(AdvisorySource.SELECTION,
                "osv, osv-mirror")));
        task.repository(new Pass(tenant.scope("other"), "other", Map.of()));
        task.completed(Instant.now());

        assertThat(mirror.asked).containsExactly(Set.of("Maven"));
        assertThat(mirror.refreshed).as("told before it refreshed").isEqualTo(1);

        task.repository(new Pass(tenant.scope("other"), "other", Map.of()));
        task.completed(Instant.now());

        assertThat(mirror.asked.getLast()).as("nothing names it any more").isEmpty();
    }

    /** A mirror recording what it was asked to keep and how often it refreshed. */
    private static final class Mirror implements AdvisorySource.Mirror {

        private final List<Set<String>> asked = new ArrayList<>();
        private int refreshed;

        @Override
        public void mirror(Set<String> ecosystems) {
            asked.add(Set.copyOf(ecosystems));
        }

        @Override
        public Freshness refresh() {
            refreshed++;
            return Freshness.FIXED;
        }

        @Override
        public Optional<String> snapshot() {
            return Optional.empty();
        }

        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            return List.of();
        }

        @Override
        public Set<String> ecosystems() {
            return Set.of();
        }

        @Override
        public Freshness freshness() {
            return Freshness.FIXED;
        }
    }

    /** One repository's visit with {@code settings} as its effective configuration. */
    private record Pass(ArtifactStore store, String repository, Map<String, String> settings)
            implements RepositoryContext {

        @Override
        public String tenant() {
            return "default";
        }

        @Override
        public UnaryOperator<String> config() {
            return settings::get;
        }

        @Override
        public UnitFailures failures(String work, String consequence) {
            return new UnitFailures(work, consequence);
        }

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public void gauge(String name, String description, Map<String, String> tags, double value) {
        }

        @Override
        public TenantView tenantView() {
            return TenantView.NONE;
        }
    }
}
