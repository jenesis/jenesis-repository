package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.InspectionMerge;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback that stops a padded archive publishing un-screened, and the way it used to be switched off by accident.
 *
 * <h2>What this is about</h2>
 *
 * An inspector reads a bounded prefix of an artifact. A publisher who puts the declaration <em>beyond</em> that bound
 * gets an empty read, and an empty read must not be mistaken for "there is nothing here to gate" - or padding an
 * archive would be enough to proxy and publish through the gate with nothing logged. So both screens fall back to a
 * coordinate derived from the request path, which gives the license and deny-list dimensions something to bite on, and
 * record that the screen was incomplete.
 *
 * <h2>The defect</h2>
 *
 * Both screens guarded that fallback on <b>the subject list being empty</b>, when the question they meant to ask is
 * whether any <b>package</b> subject came back. The two differ exactly when a second inspector claims the same path: a
 * content-scan subject - a detected secret, an inbound attestation, a publisher's signature - makes the list non-empty
 * while carrying no licensable identity, and the license dimension skips it by design. So any content inspector that
 * found something in a truncated head silently disabled the fallback for that artifact, and it was served.
 *
 * <p>It was found when an always-claiming signature inspector was installed and an artifact that had been held started
 * streaming through. The hole was already reachable through the embedded-secret scanner; it had simply never found
 * anything in a truncated head in a test. Both legs are fixed through one predicate
 * ({@link InspectionMerge#noPackageSubject}) rather than two copies of the condition, because two copies of a rule is
 * how the two legs drift.
 *
 * <p>These tests are the mutation that proves the fix bites: revert either guard to {@code subjects.isEmpty()} and the
 * publish leg admits the artifact instead of holding it.
 */
class TruncatedFallbackTest {

    /** A prefix bound small enough that a short body is already past it, so the truncated leg is exercised without
     *  moving 32 MiB through the screen. It is a JVM-global system property, so it is cleared in {@link #restore} -
     *  a value left behind decides the next class's inspection. */
    private static final int TINY_PREFIX = 64;

    private static String previousPrefix;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeAll
    static void shrinkTheInspectionWindow() {
        previousPrefix = System.getProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY);
        System.setProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY, Integer.toString(TINY_PREFIX));
    }

    @AfterAll
    static void restore() {
        if (previousPrefix == null) {
            System.clearProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY);
        } else {
            System.setProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY, previousPrefix);
        }
    }

    @BeforeEach
    void store() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_truncated_artifact_yielding_only_a_content_finding_is_still_held() throws IOException {
        // The regression. The inspector returns a content-scan subject from the truncated head and no package
        // subject; before the fix the non-empty list switched the fallback off, the license dimension skipped the
        // content subject, and a padded archive published clean.
        Publication.Published published = publish("/gatetest/contentonly/padded-1.0.zip", body());

        assertThat(published.disposition())
                .as("a truncated artifact with no package subject is held, whatever content findings came back")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void the_hold_names_the_coordinate_the_fallback_derived_from_the_path() throws IOException {
        publish("/gatetest/contentonly/padded-1.0.zip", body());

        // The fallback put a licensable coordinate in front of the gate, which is the whole job: the license dimension
        // had something to bite on and the hold names the artifact rather than the content finding.
        assertThat(reasons()).anySatisfy(reason -> assertThat(reason).contains("No license declared"));
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.coordinate()).contains("padded-1.0.zip"));
    }

    @Test
    void a_truncated_artifact_that_yields_a_package_subject_is_gated_on_that_subject_not_on_its_filename()
            throws IOException {
        // The fallback must not fire when the inspectors DID read a package. This one is still held - it declares no
        // license, and with the license dimension installed that is a hold either way - but the coordinate it is held
        // under is the one the inspector read, not the filename. That is the observable difference between the
        // fallback firing and not firing, and asserting the disposition alone could not tell them apart.
        publish("/gatetest/clean/lib-1.0.jar", body());

        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event -> {
            assertThat(event.coordinate()).isEqualTo("org.clean:lib:1.0");
            assertThat(event.coordinate()).doesNotContain("lib-1.0.jar");
        });
    }

    @Test
    void the_predicate_reads_a_list_of_content_subjects_as_carrying_no_package() {
        ComplianceGate.Subject pkg = new ComplianceGate.Subject("test", "org:lib", "1.0",
                List.of(new ComplianceGate.DeclaredLicense("Apache-2.0", null)));
        ComplianceGate.Subject content = new ComplianceGate.Subject("test", "", "", List.of())
                .withSecrets(List.of(new ComplianceGate.DetectedSecret("k", "d", "****", "p")));

        assertThat(InspectionMerge.noPackageSubject(List.of())).isTrue();
        assertThat(InspectionMerge.noPackageSubject(List.of(content))).isTrue();
        assertThat(InspectionMerge.noPackageSubject(List.of(content, content))).isTrue();
        assertThat(InspectionMerge.noPackageSubject(List.of(pkg))).isFalse();
        assertThat(InspectionMerge.noPackageSubject(List.of(content, pkg))).isFalse();
    }

    /** Holds a package subject that declares no licence, and has nothing to say about a content-scan subject. */
    private static final GatePolicy UNDECLARED_LICENCE = (subject, advisories) ->
            subject.contentScan() || !subject.licenses().isEmpty()
                    ? List.of()
                    : List.of(new ComplianceGate.Finding(Verdict.QUARANTINE, "No license declared"));

    /** A body comfortably past {@link #TINY_PREFIX}, so every inspector's read is truncated. */
    private static byte[] body() {
        byte[] padded = new byte[TINY_PREFIX * 4];
        Arrays.fill(padded, (byte) 'x');
        return padded;
    }

    private Publication.Published publish(String path, byte[] body) throws IOException {
        // This suite is about whether the FALLBACK routes a truncated artifact to a dimension at all, so it needs a
        // dimension that bites on the coordinate the fallback derives and skips a content-scan subject - which is
        // the shape of a licence dimension holding an undeclared licence. It is stated here rather than discovered,
        // so the test is about its own subject instead of about whichever dimensions a module path carries.
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), AdvisorySource.none())
                .policies(List.of(UNDECLARED_LICENCE));
        Publication publication = new Publication(store, List.of(new ComplianceScreen(() -> gate)));
        Publication.Published outcome = publication.screen(ArtifactDescriptor.at("test", path),
                new ByteArrayInputStream(body));
        if (outcome.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            publication.link(path, outcome.hash());
        }
        return outcome;
    }

    private List<String> reasons() throws IOException {
        return new QuarantineLog(store).events().stream()
                .flatMap(event -> event.reasons().stream())
                .toList();
    }
}
