package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three core gate dimensions each reach every verdict from configuration.
 *
 * <p>Two of them did not. {@code VulnerabilityPolicy.action} and {@code DenyListPolicy.action} were public and
 * honoured where they are read, and nothing in {@code source/**} ever called them: {@code LiveConfig} and the
 * migration rescreen task both constructed the policies and named no verdict, so {@code REJECT} was the only verdict
 * a deployment could run. The malicious-package dimension has had {@code malware-action} throughout, and the seven
 * discovered dimensions each carry their own dial, so an operator could soften malware to {@code QUARANTINE} but
 * could neither soften the deny list nor move the vulnerability dimension at all.
 *
 * <p>This drives the dials through the surface an operator actually sets - a stored setting read by
 * {@code LiveConfig} - rather than by calling the withers directly, because "the wither works" was never in doubt.
 * What was missing is a path from configuration to it, and only a test that starts at the configuration can tell
 * the difference.
 */
class CoreGateVerdictDialTest {

    private static final String ECO = "maven";
    private static final String COORD = "org.apache.logging.log4j:log4j-core";
    private static final String VERSION = "2.14.1";
    private static final String CVE = "CVE-2021-44228";

    /** A feed that scores the subject CRITICAL, so the vulnerability dimension has something to reach a verdict on. */
    private static final AdvisorySource FEED = AdvisorySource.of(Map.of(COORD,
            List.of(new AdvisorySource.Advisory(CVE, Severity.CRITICAL, false, "2.15.0", List.of(CVE)))));

    private static final ComplianceGate.Subject SUBJECT =
            new ComplianceGate.Subject(ECO, COORD, VERSION, List.of());

    @TempDir
    Path root;

    private Settings settings;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        settings = new Settings(store);
    }

    /** A gate resolved from the stored settings, over the feed above. The store is fresh per test method, and
     *  within one method each call overwrites the keys it names - so a leg that tightens a dial after loosening it
     *  reads the tightened value, which is the sequence these legs actually drive. */
    private ComplianceGate gate(Map<String, String> stored) throws IOException {
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            settings.set(entry.getKey(), entry.getValue());
        }
        return new LiveConfig(settings, new RepositoryProperties(), FEED, _ -> null).publishGate();
    }

    /**
     * The verdict <em>this dimension</em> reached, found by the detail text it writes, or empty when it found
     * nothing at all.
     *
     * <p>The gate's own {@code verdict()} is the worst across every dimension, discovered ones included, and this
     * module's graph carries several that have their own opinion about a subject with no attestation and no declared
     * licence. Asserting on the rolled-up verdict would therefore be asserting on which plugins happen to be
     * installed beside the one under test. Reading the finding keeps the claim about the dial.
     */
    private static Optional<Verdict> verdictFor(ComplianceGate gate, String marker) {
        return gate.assess(SUBJECT).findings().stream()
                .filter(finding -> finding.detail().contains(marker))
                .map(ComplianceGate.Finding::verdict)
                .findFirst();
    }

    @Test
    void the_vulnerability_dimension_reaches_every_verdict_from_configuration() throws IOException {
        assertThat(verdictFor(gate(Map.of()), CVE))
                .as("the packaged default refuses, and stays the secure floor")
                .contains(Verdict.REJECT);
        assertThat(verdictFor(gate(Map.of("vulnerability-action", "QUARANTINE")), CVE))
                .as("an operator can hold a vulnerable artifact for review instead of refusing it")
                .contains(Verdict.QUARANTINE);
        assertThat(verdictFor(gate(Map.of("vulnerability-action", "ALLOW")), CVE))
                .as("and can evaluate-and-permit, the verdict the malicious-package dimension always offered")
                .contains(Verdict.ALLOW);
    }

    @Test
    void the_deny_list_dimension_reaches_every_verdict_from_configuration() throws IOException {
        Map<String, String> denied = Map.of("deny-list", COORD, "vulnerability-threshold", "NONE");

        assertThat(verdictFor(gate(new LinkedHashMap<>(denied)), COORD))
                .as("the packaged default refuses a denied coordinate")
                .contains(Verdict.REJECT);

        Map<String, String> held = new LinkedHashMap<>(denied);
        held.put("deny-list-action", "QUARANTINE");
        assertThat(verdictFor(gate(held), COORD))
                .as("an operator can hold a denied coordinate for review")
                .contains(Verdict.QUARANTINE);

        Map<String, String> served = new LinkedHashMap<>(held);
        served.put("deny-list-action", "ALLOW");
        assertThat(verdictFor(gate(served), COORD))
                .as("and can permit one, which no configuration could reach before")
                .contains(Verdict.ALLOW);
    }

    /**
     * The threshold and the verdict are independent: switching the check off is not the same as permitting what it
     * finds, and neither dial silently implies the other.
     */
    @Test
    void a_disabled_threshold_is_not_the_same_dial_as_an_admitting_verdict() throws IOException {
        assertThat(verdictFor(gate(Map.of("vulnerability-threshold", "NONE")), CVE))
                .as("nothing is evaluated, so the dimension contributes no finding at all")
                .isEmpty();
        assertThat(verdictFor(gate(Map.of("vulnerability-threshold", "NONE", "vulnerability-action", "REJECT")), CVE))
                .as("and a REJECT verdict does not resurrect a check that is switched off")
                .isEmpty();
    }
}
