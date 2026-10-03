package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.ManualHold;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.store.GatedRepository;
import build.jenesis.repository.gate.store.HoldLifecycle;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A version is reviewed whole: a file arriving at a version already held for review lands held with it, whatever the
 * gate thinks of the file alone, and a release or a discard of one of its files acts on every held file of it.
 */
class VersionReviewTest {

    private static final String ECOSYSTEM = "Maven";
    private static final String COORD = "org.vuln:lib";
    private static final String VERSION = "1.0";
    private static final String JAR = "/maven/org/vuln/lib/1.0/lib-1.0.jar";
    private static final String POM = "/maven/org/vuln/lib/1.0/lib-1.0.pom";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    /** Hold {@code path} of the version for review, as a screen or a sweep leaves it. */
    private void hold(String path) throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.record(store, path, ECOSYSTEM, COORD, VERSION);
        publication.link("/quarantine" + path, publication.storeBlob(
                new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
    }

    /** Publish {@code path} of the version through a gate that finds nothing against it. */
    private Publication.Published publish(String path) throws IOException {
        ComplianceGate clean = new ComplianceGate(new VulnerabilityPolicy(Severity.CRITICAL, Verdict.QUARANTINE),
                AdvisorySource.none());
        Publication publication = new Publication(store, List.of(new ComplianceScreen(() -> clean)));
        return publication.screen(new ArtifactDescriptor(ECOSYSTEM, COORD, VERSION, path, null, false, null, -1L),
                new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_file_arriving_at_a_held_version_lands_held_with_it() throws IOException {
        hold(JAR);
        String notes = "/raw/org/vuln/lib/1.0/notes.txt";

        assertThat(publish(notes).disposition()).as("a file the gate finds nothing against, of a held version")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(new QuarantineLog(store).latest(notes)).get().satisfies(event ->
                assertThat(event.rules()).as("held for its version").containsExactly("Version held"));
        assertThat(HeldSubjects.paths(store, ECOSYSTEM, COORD, VERSION)).as("and now one of the version's holds")
                .contains(JAR, notes);
    }

    @Test
    void a_file_of_a_version_held_nowhere_else_is_screened_on_its_own() throws IOException {
        assertThat(publish("/raw/org/vuln/lib/1.0/notes.txt").disposition())
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);

        hold(JAR);
        publish(JAR);
        assertThat(new QuarantineLog(store).latest(JAR).map(QuarantineLog.Event::rules).orElse(List.of()))
                .as("a republish of the held file itself is the screen's own to judge, not held for its version")
                .doesNotContain("Version held");
    }

    @Test
    void a_release_of_one_file_releases_every_held_file_of_its_version() throws IOException {
        hold(JAR);
        hold(POM);

        assertThat(new GatedRepository(store).release(JAR)).containsExactly(JAR, POM);

        Publication publication = new Publication(store);
        assertThat(publication.blob("/quarantine" + JAR)).isEmpty();
        assertThat(publication.blob("/quarantine" + POM)).as("released with its version").isEmpty();
        assertThat(publication.located(POM)).as("and serving").isPresent();
        assertThat(HeldSubjects.paths(store, ECOSYSTEM, COORD, VERSION)).isEmpty();
    }

    @Test
    void a_discard_of_one_file_discards_every_held_file_of_its_version() throws IOException {
        hold(JAR);
        hold(POM);

        assertThat(new GatedRepository(store).discard(POM)).as("the version's held files").containsExactly(POM, JAR);

        Publication publication = new Publication(store);
        assertThat(publication.blob("/quarantine" + JAR)).as("discarded with its version").isEmpty();
        assertThat(publication.located(JAR)).isEmpty();
        assertThat(new GatedRepository(store).discard(POM)).as("nothing is left to discard").isEmpty();
    }

    @Test
    void an_operator_can_hold_any_version_and_it_is_released_like_any_other() throws IOException {
        Publication publication = new Publication(store);
        publication.link(JAR, publication.storeBlob(new ByteArrayInputStream("jar".getBytes(StandardCharsets.UTF_8))));
        publication.link(POM, publication.storeBlob(new ByteArrayInputStream("pom".getBytes(StandardCharsets.UTF_8))));

        assertThat(HoldLifecycle.holdVersion(store, ECOSYSTEM, COORD, VERSION, "keylogin/admin")).isTrue();

        assertThat(publication.located(JAR)).as("held, so not served").isEmpty();
        assertThat(publication.located(POM)).isEmpty();
        assertThat(new QuarantineLog(store).latest(POM)).get().satisfies(event -> {
            assertThat(event.rules()).containsExactly(ManualHold.RULE);
            assertThat(event.reasons()).containsExactly("Held for review by keylogin/admin");
        });
        assertThat(ManualHold.held(store, ECOSYSTEM, COORD, VERSION)).as("who held it").get()
                .isEqualTo(Set.of("keylogin/admin"));

        assertThat(new GatedRepository(store).release(POM)).containsExactlyInAnyOrder(JAR, POM);
        assertThat(publication.located(JAR)).as("released, so served again").isPresent();
        assertThat(ManualHold.held(store, ECOSYSTEM, COORD, VERSION)).as("the operator's record is consumed").isEmpty();
    }

    @Test
    void a_version_that_serves_nothing_is_not_held() throws IOException {
        assertThat(HoldLifecycle.holdVersion(store, ECOSYSTEM, COORD, "9.9", "keylogin/admin")).isFalse();
    }
}
