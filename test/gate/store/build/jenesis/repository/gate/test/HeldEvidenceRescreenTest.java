package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A held artifact re-assessed once evidence it was waiting on lands off the publish path - a content scan's report on
 * an image - through the tenant's own publish gate, bound to the artifact as it was stored: it is released when the
 * gate now clears it, and stays held, saying why, when the evidence is itself a reason to hold. Driven through a real
 * {@code docker push}-shaped manifest PUT into the OCI format, screened by the discovered {@link ComplianceScreen}
 * over the OCI inspector through the binding the store carries, with a dimension that reads the repository's record
 * of the scan.
 */
class HeldEvidenceRescreenTest {

    private static final String TYPE = "application/vnd.oci.image.manifest.v1+json";

    private static final String PATH = "/v2/team/app/manifests/1.0";

    @TempDir
    Path root;

    private ArtifactStore store;

    private final RepositoryFormat format = RepositoryFormat.installed("oci").orElseThrow();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** Holds an image whose stored manifest the repository has no scan record for, and reports what a recorded scan
     *  found as advisories - the shape of a dimension waiting on a content scanner. */
    private record ScanEvidence(ArtifactStore repository, ArtifactDescriptor artifact) implements GatePolicy {

        private Optional<String> recorded() {
            if (repository == null || artifact == null || artifact.hash() == null) {
                return Optional.empty();
            }
            try {
                return repository.readVersioned("scanned/" + artifact.hash())
                        .map(record -> new String(record.content(), StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }

        @Override
        public String rule() {
            return "Content scan";
        }

        @Override
        public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                                   List<AdvisorySource.Advisory> advisories) {
            return recorded().isPresent() ? List.of()
                    : List.of(new ComplianceGate.Finding(Verdict.QUARANTINE, "content scan pending"));
        }

        @Override
        public List<AdvisorySource.Advisory> advisories(ComplianceGate.Subject subject) {
            return recorded().filter("vulnerable"::equals)
                    .map(_ -> List.of(new AdvisorySource.Advisory("CVE-2023-0286", Severity.CRITICAL, false,
                            "openssl 3.0.8", List.of("CVE-2023-0286"), "openssl 3.0.7")))
                    .orElse(List.of());
        }

        @Override
        public GatePolicy bound(ArtifactStore repository, ArtifactDescriptor artifact) {
            return new ScanEvidence(repository, artifact);
        }
    }

    private static final ComplianceGate GATE = new ComplianceGate(
            new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
            AdvisorySource.none()).policies(List.of(new ScanEvidence(null, null)));

    @Test
    void the_scan_landing_clean_releases_the_held_image() throws IOException {
        try (ComplianceScreen.Binding binding = bindGate()) {
            store = binding.bind(store);
            String hex = push();
            assertThat(new QuarantineLog(store).latest(PATH)).hasValueSatisfying(event ->
                    assertThat(event.reasons()).contains("content scan pending"));

            record(hex, "clean");

            assertThat(ComplianceScreen.rescreen(store, "default", PATH, "the content scan finished"))
                    .isEqualTo(ComplianceScreen.Rescreened.RELEASED);
            assertThat(get(PATH)).as("the released image pulls by tag").isEqualTo(200);
            assertThat(get("/v2/team/app/manifests/sha256:" + hex)).as("and by digest").isEqualTo(200);
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    void the_scan_landing_with_a_critical_finding_keeps_it_held_and_says_why() throws IOException {
        try (ComplianceScreen.Binding binding = bindGate()) {
            store = binding.bind(store);
            String hex = push();
            record(hex, "vulnerable");

            assertThat(ComplianceScreen.rescreen(store, "default", PATH, "the content scan finished"))
                    .isEqualTo(ComplianceScreen.Rescreened.HELD);
            assertThat(get(PATH)).isEqualTo(404);
            assertThat(new QuarantineLog(store).latest(PATH)).hasValueSatisfying(event -> {
                assertThat(event.reasons().getFirst()).isEqualTo("the content scan finished; still held:");
                assertThat(event.reasons()).anySatisfy(reason -> assertThat(reason).contains("CVE-2023-0286"));
                assertThat(event.reasons()).doesNotContain("content scan pending");
            });
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    void the_scan_still_pending_keeps_it_held() throws IOException {
        try (ComplianceScreen.Binding binding = bindGate()) {
            store = binding.bind(store);
            push();

            assertThat(ComplianceScreen.rescreen(store, "default", PATH, "a pass looked"))
                    .isEqualTo(ComplianceScreen.Rescreened.HELD);
            assertThat(get(PATH)).isEqualTo(404);
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    void nothing_held_and_no_gate_bound_decide_nothing() throws IOException {
        try (ComplianceScreen.Binding binding = ComplianceScreen.binding().tenantGates(_ -> GATE).open()) {
            assertThat(ComplianceScreen.rescreen(binding.bind(store), "default", PATH, "a pass looked"))
                    .isEqualTo(ComplianceScreen.Rescreened.NOT_HELD);
        }
        assertThat(ComplianceScreen.rescreen(store, "default", PATH, "a pass looked"))
                .as("with no deployment bound in the process, an unbound store is the inert screen")
                .isEqualTo(ComplianceScreen.Rescreened.UNSCREENED);
    }

    @Test
    void a_store_that_lost_the_binding_is_refused_while_a_deployment_is_bound() {
        try (ComplianceScreen.Binding _ = bindGate()) {
            assertThatThrownBy(() -> ComplianceScreen.rescreen(store, "default", PATH, "a pass looked"))
                    .as("a re-assessment through a store the deployment did not bind must not answer UNSCREENED")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carries no deployment binding");
        }
    }

    /** The deployment's binding for this suite: the one gate, for publishes and re-assessments alike. */
    private static ComplianceScreen.Binding bindGate() {
        return ComplianceScreen.binding().gate(() -> GATE).tenantGates(_ -> GATE).open();
    }

    private void record(String hex, String outcome) throws IOException {
        store.write("scanned/" + hex, new ByteArrayInputStream(outcome.getBytes(StandardCharsets.UTF_8)));
    }

    private String push() throws IOException {
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + TYPE + "\",\"config\":{\"mediaType\":"
                + "\"application/vnd.oci.image.config.v1+json\",\"digest\":\"sha256:" + "b".repeat(64)
                + "\",\"size\":2},\"layers\":[]}").getBytes(StandardCharsets.UTF_8);
        Exchange put = new Exchange("PUT", PATH, manifest);
        format.handle(put, store);
        assertThat(put.status).as("the pending scan holds the push").isEqualTo(202);
        return HexFormat.of().formatHex(sha256(manifest));
    }

    private int get(String path) throws IOException {
        Exchange get = new Exchange("GET", path, new byte[0]);
        format.handle(get, store);
        return get.status;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }

    /** A request a registry client sends, answered into memory. */
    private static final class Exchange implements DetachedExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private int status = -1;

        private Exchange(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return "Content-Type".equalsIgnoreCase(name) ? TYPE : null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return OutputStream.nullOutputStream();
        }
    }
}
