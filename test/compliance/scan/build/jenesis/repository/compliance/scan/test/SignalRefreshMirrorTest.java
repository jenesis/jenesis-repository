package build.jenesis.repository.compliance.scan.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.scan.SignalRefreshTask;
import build.jenesis.repository.compliance.scan.SignalStatus;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refresh pass tells a mirroring feed which ecosystems to keep before it refreshes it: those of the repositories
 * naming it in {@value AdvisorySource#SELECTION}, gathered from every repository the pass visits, and nothing for a
 * repository naming none - so a pass where nothing names it any more asks it to keep nothing. And it records what each
 * source holds when it is done - a mirror's copies, a refresh that failed and why - as the document the operator
 * surfaces read back.
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

    @Test
    void the_pass_records_each_source_with_a_mirror_s_copies_and_why_a_refresh_failed() throws IOException {
        Mirror mirror = new Mirror();
        ArtifactStore space = SignalStatus.space(tenant);
        SignalRefreshTask task = new SignalRefreshTask(Duration.ofMinutes(5), Map.of("osv-mirror", mirror,
                "stale", new Unreachable()), Map.of(), () -> space);
        assertThat(SignalStatus.read(space)).as("nothing is recorded before a pass").isEmpty();

        task.repository(new Pass(tenant.scope("releases"), "releases", Map.of(AdvisorySource.SELECTION,
                "osv-mirror")));
        assertThatThrownBy(() -> task.completed(Instant.now())).as("a failed draw still fails the pass")
                .isInstanceOf(IOException.class);

        SignalStatus.Status status = SignalStatus.read(space).orElseThrow();
        assertThat(status.sources()).extracting(SignalStatus.Source::name).containsExactly("osv-mirror", "stale");
        SignalStatus.Source kept = status.sources().getFirst();
        assertThat(kept.failure()).isNull();
        assertThat(kept.copies()).as("the copy the pass asked it to keep, built by its refresh")
                .containsExactly(new AdvisorySource.Mirror.Copy("Maven", Mirror.BUILT, Mirror.BUILT));
        SignalStatus.Source stale = status.sources().getLast();
        assertThat(stale.failure()).as("why it is stale, for the operator").contains("could not be reached");
        assertThat(stale.authoritative()).isFalse();
        assertThat(stale.copies()).as("a source keeping no copy lists none").isEmpty();
    }

    /** A source whose vendor never answers. */
    private static final class Unreachable implements RefreshableSource {

        @Override
        public Freshness refresh() {
            return Freshness.NEVER;
        }

        @Override
        public Optional<String> snapshot() {
            return Optional.empty();
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    }

    /** A mirror recording what it was asked to keep and how often it refreshed. */
    private static final class Mirror implements AdvisorySource.Mirror {

        /** When every copy this mirror builds was built and drawn. */
        static final Instant BUILT = Instant.parse("2026-10-05T00:00:00Z");

        private final List<Set<String>> asked = new ArrayList<>();
        private int refreshed;

        @Override
        public List<Copy> copies() {
            List<Copy> copies = new ArrayList<>();
            for (String ecosystem : asked.isEmpty() ? Set.<String>of() : new TreeSet<>(asked.getLast())) {
                copies.add(refreshed == 0 ? new Copy(ecosystem, null, null) : new Copy(ecosystem, BUILT, BUILT));
            }
            return copies;
        }

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
