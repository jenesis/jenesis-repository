package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gateway.ProxyScreen;
import build.jenesis.repository.gateway.ProxyScreenHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each {@link ScreeningMode} over a proxied fill, against a feed that fails on every call and one that flags the
 * package malicious: what is served, what is withheld, and that the deny-list refuses in every mode.
 */
class ScreeningModeTest {

    private static final String PATH = "/maven/org/acme/lib/1.0/lib-1.0.pom";
    private static final String COORDINATE = "org.acme:lib";

    /** A feed that cannot answer: every lookup raises, as a real feed does on a non-200. */
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

    /** A feed that flags the package malicious and scores it nothing. */
    private static final AdvisorySource FLAGGED = AdvisorySource.of(Map.of(COORDINATE,
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
    void hold_keeps_a_copy_the_feed_could_not_clear() throws IOException {
        assertThat(fetch(ScreeningMode.HOLD, gate(UNREACHABLE, List.of()))).isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo(Verdict.QUARANTINE);
            assertThat(event.rules()).contains(ComplianceGate.FEED_UNAVAILABLE_RULE);
        });
    }

    @Test
    void admit_serves_a_copy_every_check_that_could_answer_allows() throws IOException {
        assertThat(fetch(ScreeningMode.ADMIT, gate(UNREACHABLE, List.of())))
                .as("the outage leaves the copy to the checks that answered, and they allow it").isPresent();
        assertThat(new QuarantineLog(store).events()).as("nothing is held, and the outage it was served through is "
                + "recorded").singleElement().satisfies(event -> {
                    assertThat(event.verdict()).isEqualTo(Verdict.ALLOW);
                    assertThat(event.reasons()).anySatisfy(reason -> assertThat(reason).contains("503"));
                });
    }

    @Test
    void admit_still_refuses_a_deny_listed_copy_while_the_feed_is_down() throws IOException {
        assertThat(fetch(ScreeningMode.ADMIT, gate(UNREACHABLE, List.of(COORDINATE))))
                .as("the checks that answer still decide, and the deny-list refuses").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement()
                .satisfies(event -> assertThat(event.verdict()).isEqualTo(Verdict.REJECT));
    }

    @Test
    void admit_holds_what_a_feed_that_answers_flags() throws IOException {
        assertThat(fetch(ScreeningMode.ADMIT, gate(FLAGGED, List.of())))
                .as("admitting governs an outage, not a finding").isEmpty();
    }

    @Test
    void record_serves_what_the_screen_found() throws IOException {
        assertThat(fetch(ScreeningMode.RECORD, gate(FLAGGED, List.of())))
                .as("a flagged package is served and its finding recorded, never held").isPresent();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).as("served").isEqualTo(Verdict.ALLOW);
            assertThat(event.reasons()).as("with what the screen found").anySatisfy(reason ->
                    assertThat(reason).contains("MAL-2026-0001"));
        });
    }

    @Test
    void record_serves_a_copy_the_feed_could_not_clear() throws IOException {
        assertThat(fetch(ScreeningMode.RECORD, gate(UNREACHABLE, List.of()))).isPresent();
    }

    @Test
    void record_still_refuses_the_deny_list() throws IOException {
        assertThat(fetch(ScreeningMode.RECORD, gate(FLAGGED, List.of(COORDINATE))))
                .as("an operator's list is a refusal already made, not a finding").isEmpty();
    }

    @Test
    void record_holds_what_a_deny_list_holding_for_review_decides_beside_a_stronger_finding() throws IOException {
        // The deny-list holds for review and the malware dimension refuses, so the overall verdict is the refusal and
        // the deny-list's own rule is not among the rules that reached it - the floor is read off the findings.
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), FLAGGED)
                .malicious(new MaliciousPackagePolicy().action(Verdict.REJECT))
                .denyList(new DenyListPolicy(List.of(COORDINATE)).action(Verdict.QUARANTINE));

        assertThat(fetch(ScreeningMode.RECORD, gate)).as("the deny-list's hold stands").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement()
                .satisfies(event -> assertThat(event.verdict()).isEqualTo(Verdict.QUARANTINE));
    }

    @Test
    void the_dispatchers_hooks_read_the_mode_of_the_repository_requested() throws IOException {
        ComplianceGate gate = gate(UNREACHABLE, List.of());
        ProxyScreenHooks hooks = ProxyScreenHooks.perTenant((_, _) -> gate, () -> 0, () -> false);

        assertThat(hooks.forRequest("acme", exchange("ADMIT")).screenFetch(PATH, upstream(), store)
                .fetch(URI.create("http://up" + PATH), Map.of()))
                .as("a repository admitting through an outage serves").isPresent();
        assertThat(hooks.forRequest("acme", exchange(null)).screenFetch(PATH, upstream(), store)
                .fetch(URI.create("http://up" + PATH), Map.of()))
                .as("one that sets no mode holds").isEmpty();
    }

    private Optional<ProxyFormat.Fetched> fetch(ScreeningMode mode, ComplianceGate gate) throws IOException {
        return new ProxyScreen(gate, store, 0).screening(mode).wrap(upstream(), PATH)
                .fetch(URI.create("http://up" + PATH), Map.of());
    }

    private static ProxyFormat.Fetcher upstream() {
        return (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.of(new ProxyFormat.Fetched(200,
                ("<project><groupId>org.acme</groupId><artifactId>lib</artifactId><version>1.0</version></project>")
                        .getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    /** The malicious dial is named: these scenarios hold a flagged package rather than refuse it. */
    private static ComplianceGate gate(AdvisorySource advisories, List<String> denied) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), advisories)
                .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE))
                .denyList(new DenyListPolicy(denied));
    }

    /** A request into a repository whose screening mode is {@code mode}, or that sets none. */
    private static FormatExchange exchange(String mode) {
        return new DetachedExchange() {
            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String path() {
                return PATH;
            }

            @Override
            public String queryParameter(String name) {
                return null;
            }

            @Override
            public String requestHeader(String name) {
                return null;
            }

            @Override
            public InputStream requestStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public void setResponseHeader(String name, String value) {
            }

            @Override
            public OutputStream respond(int status, long contentLength) {
                return OutputStream.nullOutputStream();
            }

            @Override
            public String setting(String key) {
                return ScreeningMode.KEY.equals(key) ? mode : null;
            }
        };
    }
}
