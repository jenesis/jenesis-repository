package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.store.HoldLifecycle;
import build.jenesis.repository.gate.QuarantineDispatch;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review-release replay path of {@link ComplianceScreen}: {@link ComplianceScreen#replaying} sets the per-thread
 * {@code RELEASING} flag while {@link HoldLifecycle#release} re-drives a screen-quarantined upload's format dispatch, so
 * a format whose own {@code handle} re-publishes through the {@link Publication} (Maven's does) accepts the
 * already-reviewed-and-released bytes rather than re-screening them into a fresh quarantine - the release IS the
 * override of that verdict. This is driven through the real public seam ({@code HoldLifecycle.release} over a recorded
 * {@link QuarantineDispatch} and the discovered {@link GateReplayTestFormat}), the wired gate deliberately one that
 * would quarantine the replayed bytes, so the ACCEPT proves suppression and not merely an inert gate.
 *
 * <p>The second half guards the {@code finally} in {@code replaying}: once the release returns, an ordinary publish
 * through the same discovered screen must still gate. A regression that dropped the {@code RELEASING.remove()} would
 * leave the flag set and silently disable the gate for the thread - every later publish on it admitted unscreened -
 * which this pins down.
 */
class ReleaseReplaySuppressionTest {

    /** A malicious-package advisory the CVSS threshold misses (severity NONE), held by the gate's default malicious
     *  policy - the same fixture {@code ComplianceScreenTest} quarantines on, so the wired gate really bites. */
    private static final AdvisorySource MALICIOUS = AdvisorySource.of(Map.of(
            "com.mal:stealer", List.of(new AdvisorySource.Advisory("MAL-2026-0001", Severity.NONE, true))));

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        GateReplayTestFormat.reset();
    }

    @Test
    void a_release_replay_is_accepted_under_suppression_and_the_flag_does_not_leak() throws IOException {
        // The malicious dial is named rather than inherited: the shipped default is REJECT - the properties the
        // server binds, and the setting catalogue - and the control below needs a HELD upload to replay, which a
        // rejection never leaves behind.
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), MALICIOUS)
                .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE));
        try (ComplianceScreen.Wiring wiring = ComplianceScreen.live(() -> gate)) {
            // A control publish proves the wired gate is live: an ordinary malicious upload quarantines, so a later
            // ACCEPT on the same coordinate can only be the screen's suppression, not an inert gate.
            Publication.Published control = new Publication(store).screen(
                    ArtifactDescriptor.at("test", "/gatetest/malicious/control-1.0.jar"), bytes("control bytes"));
            assertThat(control.disposition())
                    .as("the wired gate quarantines a malicious upload when the screen is not suppressed")
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
            int rowsBeforeRelease = new QuarantineLog(store).events().size();

            // A screen()-quarantined hosted upload: the held blob is the raw publish envelope and the dispatch context
            // records the claiming format, exactly what the deploy choreography leaves for a later release to replay.
            String held = "/gatetest/malicious/stealer-1.0.jar";
            Publication publication = new Publication(store);
            String heldHash = publication.storeBlob(new ByteArrayInputStream("stealer bytes".getBytes(
                    StandardCharsets.UTF_8)));
            publication.link("/quarantine" + held, heldHash);
            QuarantineDispatch.record(store, held, GateReplayTestFormat.NAME, "PUT", heldHash, Map.of());

            String released = HoldLifecycle.release(store, held);

            // (a) The release replay ran with the screen suppressed: the discovered screen ACCEPTED the re-published
            // bytes even though the wired gate would have quarantined them - the released bytes are not re-quarantined.
            assertThat(GateReplayTestFormat.lastReplayDisposition())
                    .as("the release replay is accepted with the screen suppressed, not re-quarantined")
                    .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
            assertThat(released).as("the release returns the released blob's hash").isEqualTo(heldHash);
            assertThat(publication.blob("/quarantine" + held))
                    .as("the hold pointer is cleared - the released bytes are not re-held").isEmpty();
            assertThat(new QuarantineLog(store).events())
                    .as("the suppressed replay recorded no fresh quarantine of the released bytes")
                    .hasSize(rowsBeforeRelease);

            // (b) The RELEASING flag was cleared in replaying()'s finally: an ordinary publish on the same thread,
            // through the same discovered screen, still gates - a flag leak would have admitted it unscreened.
            Publication.Published after = new Publication(store).screen(
                    ArtifactDescriptor.at("test", "/gatetest/malicious/after-1.0.jar"), bytes("after bytes"));
            assertThat(after.disposition())
                    .as("no flag leak: an ordinary publish after the replay is still gated")
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        }
    }

    private static ByteArrayInputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
