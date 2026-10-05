package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gateway.PendingScreenTask;
import build.jenesis.repository.gateway.ProxyScreen;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A copy served while a feed could not answer is screened again by the pending re-screen: left serving while the feed
 * still cannot answer, held once the feed flags it, and cleared once it answers clean.
 */
class PendingScreenTaskTest {

    private static final String PATH = "/maven/org/acme/lib/1.0/lib-1.0.pom";
    private static final byte[] POM = ("<project><groupId>org.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0</version></project>").getBytes(StandardCharsets.UTF_8);

    private static final AdvisorySource UNREACHABLE = new AdvisorySource() {
        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            throw new UncheckedIOException(new IOException("the feed answered 503"));
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    };

    private static final AdvisorySource FLAGGED = AdvisorySource.of(Map.of("org.acme:lib",
            List.of(new AdvisorySource.Advisory("MAL-2026-0001", Severity.NONE, true))));

    @TempDir
    Path root;

    private ArtifactStore store;

    /** The feed every gate below asks, switched as the scenario goes on. */
    private final AtomicReference<AdvisorySource> feed = new AtomicReference<>(UNREACHABLE);

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        // A fill admitted through the outage, and the copy the pull-through then caches.
        Optional<ProxyFormat.Fetched> fetched = new ProxyScreen(gate(), store, 0).screening(ScreeningMode.ADMIT)
                .wrap((ProxyFormat.Fetcher.Buffered) (url, headers) ->
                        Optional.of(new ProxyFormat.Fetched(200, POM, Map.of())), PATH)
                .fetch(URI.create("http://up" + PATH), Map.of());
        assertThat(fetched).as("admitted through the outage").isPresent();
        Publication publication = new Publication(store);
        publication.link(PATH, publication.storeBlob(new ByteArrayInputStream(POM)));
    }

    @Test
    void a_copy_stays_pending_while_the_feed_cannot_answer_and_is_held_once_it_flags_it() throws IOException {
        assertThat(markers()).as("the admitted copy is marked").hasSize(1);

        pass();
        assertThat(markers()).as("still pending while the feed cannot answer").hasSize(1);
        assertThat(new Publication(store).located(PATH)).as("and still serving").isPresent();

        feed.set(FLAGGED);
        pass();
        assertThat(new Publication(store).located(PATH)).as("held once the feed flags it").isEmpty();
        assertThat(Publication.reviewPending(store, PATH)).as("for review").isTrue();
        assertThat(markers()).as("and no longer pending").isEmpty();
    }

    @Test
    void a_copy_the_feed_answers_clean_is_no_longer_pending() throws IOException {
        feed.set(AdvisorySource.none());
        pass();
        assertThat(markers()).isEmpty();
        assertThat(new Publication(store).located(PATH)).as("still serving").isPresent();
    }

    @Test
    void a_marker_whose_copy_serves_nothing_is_cleared() throws IOException {
        store.delete("publish" + PATH);
        pass();
        assertThat(markers()).isEmpty();
    }

    private List<String> markers() {
        return store.list("screen-pending");
    }

    private void pass() throws IOException {
        new PendingScreenTask(Duration.ofMinutes(15), _ -> gate(), _ -> 0).repository(context());
    }

    /** A gate asking whatever {@link #feed} currently is; the malicious dial holds rather than refuses. */
    private ComplianceGate gate() {
        AdvisorySource current = new AdvisorySource() {
            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                return feed.get().advisories(ecosystem, coordinate, version);
            }

            @Override
            public Freshness freshness() {
                return feed.get().freshness();
            }
        };
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), current)
                .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE));
    }

    private RepositoryContext context() {
        return new RepositoryContext() {
            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return "releases";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> ScreeningMode.KEY.equals(key) ? ScreeningMode.ADMIT.name() : null;
            }

            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public Instant now() {
                return Instant.parse("2026-10-05T00:00:00Z");
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
