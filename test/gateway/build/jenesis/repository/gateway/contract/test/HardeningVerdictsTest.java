package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.gateway.HardenedScreen;
import build.jenesis.repository.gateway.HardeningVerdicts;
import build.jenesis.repository.gateway.VerdictSection;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read-only hardening console surface: {@link HardeningVerdicts} assembles what the hardened leg
 * durably recorded - the digest-pinned {@link VerdictSection verdict}, the typed {@link QuarantineLog} refusals and the
 * drift alarm - for a coordinate, over a seeded meta doc and quarantine ledger, <em>without</em> re-screening or
 * fetching a byte (there is no {@code ComplianceGate}, spool or fetcher in play here - the read cannot screen). Proves:
 * a recorded verdict reads back whole; recent hardened refusals surface (and an ordinary gate hold does not); a
 * coordinate never screened reads as such (the §10 staleness line, so a caller lacking the write role still sees it);
 * and the gateway-wide drift counter is surfaced.
 */
class HardeningVerdictsTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;
    private QuarantineLog quarantine;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        metadata = new TestMetadataStore(store);
        quarantine = new QuarantineLog(store);
    }

    @Test
    void the_recorded_verdict_reads_back_whole_without_rescreening() throws Exception {
        String path = "/spy/lib-1.0.spy";
        Instant screenedAt = Instant.parse("2026-07-20T10:15:30Z");
        seedVerdict(path, "abc123", Verdict.ALLOW, screenedAt,
                List.of(VerdictSection.Validator.of("CountingInspector")));

        HardeningVerdicts.View view = new HardeningVerdicts(metadata, quarantine).view(path, 10);

        assertThat(view.screened()).as("a verdict is recorded").isTrue();
        assertThat(view.screenedAt()).as("the staleness line the read shows").isEqualTo(screenedAt.toString());
        HardeningVerdicts.RecordedVerdict verdict = view.verdict();
        assertThat(verdict.verdict()).isEqualTo("ALLOW");
        assertThat(verdict.digest()).isEqualTo("sha256:abc123");
        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.profile()).isEqualTo("hardened/full-body");
        assertThat(verdict.source()).isEqualTo("http://upstream/spy/lib-1.0.spy");
        assertThat(verdict.validators()).singleElement()
                .satisfies(validator -> assertThat(validator.name()).isEqualTo("CountingInspector"));
    }

    @Test
    void recent_hardened_refusals_surface_and_an_ordinary_gate_hold_does_not() throws Exception {
        String refused = "/spy/oversize-2.0.spy";
        quarantine.record(Instant.parse("2026-07-21T09:00:00Z"), refused, refused, Verdict.REJECT,
                List.of(HardenedScreen.REFUSAL_REASON_PREFIX + HardenedScreen.Refusal.OVERSIZE + "): too big"));
        // An ordinary publish-path gate hold recorded in the same log must NOT be reported as a hardened refusal.
        String held = "/maven/org/acme/tool/1.0/tool-1.0.jar";
        quarantine.record(Instant.parse("2026-07-21T08:00:00Z"), held, held, Verdict.QUARANTINE,
                List.of("held: a vulnerability policy match"));

        List<HardeningVerdicts.Refusal> refusals = new HardeningVerdicts(metadata, quarantine).refusals(10);

        assertThat(refusals).singleElement().satisfies(refusal -> {
            assertThat(refusal.path()).isEqualTo(refused);
            assertThat(refusal.verdict()).isEqualTo("REJECT");
            assertThat(refusal.reasons()).anyMatch(reason -> reason.contains("OVERSIZE"));
        });
    }

    @Test
    void a_coordinate_never_screened_reads_as_never_screened() throws Exception {
        HardeningVerdicts.View view = new HardeningVerdicts(metadata, quarantine).view("/spy/absent-1.0.spy", 10);

        assertThat(view.screened()).as("never screened, never fabricated as clean").isFalse();
        assertThat(view.verdict()).isNull();
        assertThat(view.screenedAt()).isNull();
    }

    @Test
    void the_drift_alarm_counter_is_surfaced() throws Exception {
        HardeningVerdicts.View view = new HardeningVerdicts(metadata, quarantine).view("/spy/lib-1.0.spy", 10);

        // The read surfaces the gateway-wide drift counter verbatim (HardeningObservability reports the same one);
        // it is a process-wide static, so the read must reflect exactly its current value, not a fabricated zero.
        assertThat(view.drift().alarms()).isEqualTo(HardenedScreen.driftEvents());
        assertThat(view.drift().alarming()).isEqualTo(HardenedScreen.driftEvents() > 0);
    }

    @Test
    void a_missing_metadata_module_degrades_the_verdict_to_absent_not_fabricated() throws Exception {
        HardeningVerdicts.View view = new HardeningVerdicts(null, quarantine).view("/spy/lib-1.0.spy", 10);

        assertThat(view.screened()).as("no persistence installed: the leg records none, the read shows none").isFalse();
        assertThat(view.verdict()).isNull();
    }

    private void seedVerdict(String path, String digest, Verdict verdict, Instant screenedAt,
                             List<VerdictSection.Validator> validators) throws IOException {
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        metadata.mutate(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), VerdictSection.TAG,
                VerdictSection.record(digest, verdict, null, "hardened/full-body", "http://upstream" + path,
                        validators, screenedAt, QualityInspector.fullBodyInspectionLimit()));
    }
}
