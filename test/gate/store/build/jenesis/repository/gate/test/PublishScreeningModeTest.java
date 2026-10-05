package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publish screen under each {@link ScreeningMode}, read from the effective lookup the deployment binds to the
 * repository's store: what an outage and a finding come to, and that the deny-list refuses in every mode.
 */
class PublishScreeningModeTest {

    /** A feed that cannot answer: every lookup raises, as a real feed does on a non-200. */
    private static final AdvisorySource UNREACHABLE = new AdvisorySource() {
        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            throw new UncheckedIOException(new IOException("advisory feed unreachable (rate limited)"));
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    };

    /** A feed that flags the test inspector's malicious coordinate. */
    private static final AdvisorySource FLAGGED = AdvisorySource.of(Map.of("com.mal:stealer",
            List.of(new AdvisorySource.Advisory("MAL-2026-0001", Severity.NONE, true))));

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void hold_keeps_an_upload_the_feed_could_not_clear() throws IOException {
        assertThat(publish(ScreeningMode.HOLD, gate(UNREACHABLE, List.of()), "/gatetest/clean/lib-1.0.jar"))
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void admit_accepts_an_upload_every_check_that_could_answer_allows_and_records_the_outage() throws IOException {
        String path = "/gatetest/clean/lib-1.0.jar";

        assertThat(publish(ScreeningMode.ADMIT, gate(UNREACHABLE, List.of()), path))
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo(Verdict.ALLOW);
            assertThat(event.reasons()).anySatisfy(reason -> assertThat(reason).contains("rate limited"));
        });
    }

    @Test
    void admit_still_refuses_a_deny_listed_upload_while_the_feed_is_down() throws IOException {
        assertThat(publish(ScreeningMode.ADMIT, gate(UNREACHABLE, List.of("com.deny:*")),
                "/gatetest/deny/pkg-1.0.jar")).isEqualTo(PublishInterceptor.Disposition.REJECT);
    }

    @Test
    void record_accepts_what_the_screen_found_and_records_it() throws IOException {
        String path = "/gatetest/malicious/stealer-1.0.jar";

        assertThat(publish(ScreeningMode.RECORD, gate(FLAGGED, List.of()), path))
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(new Publication(store).located(path)).as("served").isPresent();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo(Verdict.ALLOW);
            assertThat(event.reasons()).as("with what the screen found").anySatisfy(reason ->
                    assertThat(reason).contains("MAL-2026-0001"));
        });
    }

    @Test
    void record_still_refuses_the_deny_list() throws IOException {
        assertThat(publish(ScreeningMode.RECORD, gate(FLAGGED, List.of("com.deny:*")), "/gatetest/deny/pkg-1.0.jar"))
                .isEqualTo(PublishInterceptor.Disposition.REJECT);
    }

    private PublishInterceptor.Disposition publish(ScreeningMode mode, ComplianceGate gate, String path)
            throws IOException {
        ArtifactStore repository = ComplianceSettings.bind(store,
                () -> key -> ScreeningMode.KEY.equals(key) ? mode.name() : null);
        Publication publication = new Publication(repository, List.of(new ComplianceScreen(() -> gate)));
        ArtifactDescriptor descriptor = ArtifactDescriptor.at("test", path);
        Publication.Published outcome = publication.screen(descriptor,
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        if (outcome.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            publication.link(descriptor.path(), outcome.hash());
        }
        return outcome.disposition();
    }

    /** The malicious dial is named: these scenarios hold a flagged package rather than refuse it. */
    private static ComplianceGate gate(AdvisorySource advisories, List<String> denied) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), advisories)
                .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE))
                .denyList(new DenyListPolicy(denied));
    }
}
