package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.compliance.Waiver;
import build.jenesis.repository.compliance.Waivers;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accept-risk waivers recorded on the findings substrate: a waiver lands as an {@code accept-risk} annotation on the
 * still-present finding with the expiry as its value, the ledger projects the active ones as the gate's ecosystem-
 * neutral {@link Waiver} matcher, a revoke relabels rather than deletes, an expiry auto-lapses without a sweep, and the
 * contract refuses a past expiry, a missing finding and a non-advisory kind. Exercised end to end through the
 * {@code ArtifactStore} SPI over a real filesystem store.
 */
class WaiverLabelsTest {

    private static final String ECO = "Maven";
    private static final String COORD = "org.apache.logging.log4j:log4j-core";
    private static final String VERSION = "2.14.1";
    private static final Instant GRANTED = Instant.parse("2026-07-13T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-08-13T00:00:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path root;

    private Findings ledger;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ledger = new StoreFindings(store);
    }

    private void recordVulnerability() throws IOException {
        Finding finding = Finding.of("CVE-2021-44228", "osv", Finding.Kind.VULNERABILITY, "advisory",
                        Severity.CRITICAL, "Log4Shell", GRANTED)
                .withReferences(List.of("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"));
        ledger.record(ECO, COORD, VERSION, finding);
    }

    @Test
    void a_waiver_lands_as_an_annotation_and_is_active_until_expiry() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES,
                "Accepted for the staging repo pending the 2.17 bump.", GRANTED);

        Finding stored = ledger.of(ECO, COORD, VERSION).getFirst();
        assertThat(stored.labels()).anyMatch(label ->
                label.source().equals("operator") && label.name().equals("accept-risk")
                        && label.value().equals(EXPIRES.toString()));
        assertThat(WaiverLabels.active(stored, GRANTED)).isTrue();
        assertThat(WaiverLabels.active(stored, AFTER_EXPIRY)).isFalse();
        assertThat(WaiverLabels.expiryOf(stored)).contains(EXPIRES);
        assertThat(WaiverLabels.noteOf(stored)).get().asString().contains("staging repo");
    }

    @Test
    void expiries_badges_the_advisory_id_and_every_cve_alias_of_an_active_waiver() throws IOException {
        // A GHSA-identified advisory that also carries a CVE alias, so the badge map must key by both the advisory id
        // AND every CVE-prefixed reference (a merged vulnerability row is looked up by either identifier).
        Finding advisory = Finding.of("GHSA-jfh8-c2jp-5v3q", "github", Finding.Kind.VULNERABILITY, "advisory",
                        Severity.CRITICAL, "Log4Shell", GRANTED)
                .withReferences(List.of("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"));
        ledger.record(ECO, COORD, VERSION, advisory);
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "github", "GHSA-jfh8-c2jp-5v3q", EXPIRES, null, GRANTED);
        List<Finding> findings = ledger.of(ECO, COORD, VERSION);

        Map<String, String> badges = WaiverLabels.expiries(findings, GRANTED);
        assertThat(badges).as("the advisory id and each CVE alias key the standing-waiver badge to its expiry instant")
                .containsEntry("GHSA-jfh8-c2jp-5v3q", EXPIRES.toString())
                .containsEntry("CVE-2021-44228", EXPIRES.toString());

        // A query past the expiry drops the badge, and a revoked waiver contributes nothing - the badge only ever
        // shows a still-standing acceptance.
        assertThat(WaiverLabels.expiries(findings, AFTER_EXPIRY))
                .as("an expired waiver is not badged").isEmpty();
        WaiverLabels.revoke(ledger, ECO, COORD, VERSION, "github", "GHSA-jfh8-c2jp-5v3q", GRANTED);
        assertThat(WaiverLabels.expiries(ledger.of(ECO, COORD, VERSION), GRANTED))
                .as("a revoked waiver is not badged").isEmpty();
    }

    @Test
    void the_ledger_projects_active_waivers_as_the_gate_matcher() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES, null, GRANTED);

        List<Waiver> waivers = WaiverLabels.waivers(ledger, GRANTED);
        assertThat(waivers).singleElement().satisfies(waiver -> {
            assertThat(waiver.covers("CVE-2021-44228", List.of())).isTrue();
            assertThat(waiver.covers("GHSA-jfh8-c2jp-5v3q", List.of())).isTrue();
            assertThat(waiver.appliesTo(ECO, COORD, VERSION)).isTrue();
            assertThat(waiver.appliesTo(ECO, "org.other:thing", VERSION)).isFalse();
            assertThat(waiver.active(GRANTED)).isTrue();
            assertThat(waiver.expires()).isEqualTo(EXPIRES);
        });
    }

    @Test
    void the_overlay_lets_the_gate_allow_a_waived_coordinate() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES, null, GRANTED);

        AdvisorySource feed = AdvisorySource.of(Map.of(COORD,
                List.of(new AdvisorySource.Advisory("CVE-2021-44228", Severity.CRITICAL, false, "2.15.0",
                        List.of("CVE-2021-44228")))));
        ComplianceGate.Subject subject = new ComplianceGate.Subject(ECO, COORD, VERSION, List.of());

        // The whole ledger-to-gate mechanism this fix wires: without the overlay the critical advisory rejects; the
        // ledger-backed overlay (the mirror of VexStore.asVex()) downgrades it to an informational allow.
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed).assess(subject).verdict())
                .isEqualTo(Verdict.REJECT);
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed)
                .waivers(WaiverLabels.overlay(ledger, GRANTED)).assess(subject).verdict())
                .isEqualTo(Verdict.ALLOW);

        // A revoked waiver stops projecting into the overlay, so the gate rejects again - no side store to forget.
        WaiverLabels.revoke(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", GRANTED);
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed)
                .waivers(WaiverLabels.overlay(ledger, GRANTED)).assess(subject).verdict())
                .isEqualTo(Verdict.REJECT);
    }

    @Test
    void the_scoped_overlay_covers_only_the_assessed_coordinate_and_never_walks_the_ledger() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES, null, GRANTED);
        // A standing waiver on a DIFFERENT coordinate that the whole-ledger overlay would project but this publish
        // must not pay for and must not honour on the coordinate it is assessing.
        String other = "com.fasterxml.jackson.core:jackson-databind";
        String otherVersion = "2.9.10";
        ledger.record(ECO, other, otherVersion, Finding.of("CVE-2019-14379", "osv", Finding.Kind.VULNERABILITY,
                "advisory", Severity.CRITICAL, "unrelated advisory", GRANTED).withReferences(List.of("CVE-2019-14379")));
        WaiverLabels.apply(ledger, ECO, other, otherVersion, "osv", "CVE-2019-14379", EXPIRES, null, GRANTED);

        AdvisorySource feed = AdvisorySource.of(Map.of(
                COORD, List.of(new AdvisorySource.Advisory("CVE-2021-44228", Severity.CRITICAL, false, "2.15.0",
                        List.of("CVE-2021-44228"))),
                other, List.of(new AdvisorySource.Advisory("CVE-2019-14379", Severity.CRITICAL, false, "2.9.10.1",
                        List.of("CVE-2019-14379")))));
        ComplianceGate.Subject assessed = new ComplianceGate.Subject(ECO, COORD, VERSION, List.of());
        ComplianceGate.Subject elsewhere = new ComplianceGate.Subject(ECO, other, otherVersion, List.of());

        Waivers scoped = WaiverLabels.overlayFor(ledger, List.of(assessed), GRANTED);
        // The assessed coordinate's own waiver stands; the unrelated coordinate's waiver never leaks into the overlay.
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed).waivers(scoped)
                .assess(assessed).verdict()).as("the assessed coordinate's waiver suppresses its finding")
                .isEqualTo(Verdict.ALLOW);
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed).waivers(scoped)
                .assess(elsewhere).verdict()).as("an unrelated coordinate's waiver is absent from a scoped overlay")
                .isEqualTo(Verdict.REJECT);

        // The architectural point: the coordinate-scoped overlay is point lookups only (no listing walk of the ledger),
        // while the whole-ledger overlay lists the tree - the O(ledger)-per-publish cost this fix removes.
        CountingStore counting = new CountingStore(ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));
        Findings counted = new StoreFindings(counting);
        WaiverLabels.overlayFor(counted, List.of(assessed), GRANTED);
        assertThat(counting.lists()).as("the coordinate-scoped overlay makes no listing walk").isZero();
        counting.reset();
        WaiverLabels.overlay(counted, GRANTED);
        assertThat(counting.lists()).as("the whole-ledger overlay walks the tree").isPositive();
    }

    /** A store that counts the {@code list} (walk) calls made through it, delegating everything else, so a test can
     *  prove a read path is a point lookup rather than a listing walk. */
    private static final class CountingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private int lists;

        private CountingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        int lists() {
            return lists;
        }

        void reset() {
            lists = 0;
        }

        @Override
        public List<String> list(String prefix) {
            lists++;
            return delegate.list(prefix);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    @Test
    void an_expired_waiver_is_not_projected() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES, null, GRANTED);

        assertThat(WaiverLabels.waivers(ledger, AFTER_EXPIRY)).isEmpty();
    }

    @Test
    void a_revoke_relabels_and_stops_projecting_without_deleting_the_finding() throws IOException {
        recordVulnerability();
        WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", EXPIRES, null, GRANTED);
        WaiverLabels.revoke(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228", GRANTED);

        assertThat(WaiverLabels.waivers(ledger, GRANTED)).isEmpty();
        // The finding itself stays fully present - a waiver is categorize-never-discard, like every annotation.
        assertThat(ledger.of(ECO, COORD, VERSION)).singleElement()
                .satisfies(finding -> assertThat(finding.id()).isEqualTo("CVE-2021-44228"));
    }

    @Test
    void a_past_expiry_is_rejected() throws IOException {
        recordVulnerability();
        assertThatThrownBy(() -> WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228",
                GRANTED.minusSeconds(1), null, GRANTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_missing_finding_is_rejected() {
        assertThatThrownBy(() -> WaiverLabels.apply(ledger, ECO, COORD, VERSION, "osv", "CVE-2021-44228",
                EXPIRES, null, GRANTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_non_advisory_finding_cannot_be_waived() throws IOException {
        Finding license = Finding.of("gpl-3.0", "licenses", Finding.Kind.LICENSE, "license", Severity.NONE,
                "Copyleft licence", GRANTED);
        ledger.record(ECO, COORD, VERSION, license);

        assertThatThrownBy(() -> WaiverLabels.apply(ledger, ECO, COORD, VERSION, "licenses", "gpl-3.0",
                EXPIRES, null, GRANTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
