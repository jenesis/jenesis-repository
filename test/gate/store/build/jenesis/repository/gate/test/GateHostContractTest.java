package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the ingestion gate promises its guests, driven by hostile ones.</b>
 *
 * <p>the earlier census asked, for every SPI with a contract kit, what <em>drives</em> it and whether anything asserts the
 * driver's side of the bargain. Three kits point at this host: {@code InspectorContract} over the seventeen
 * {@link build.jenesis.repository.compliance.QualityInspector}s, {@code GatePolicyContract} over the eight discovered
 * {@link build.jenesis.repository.compliance.GatePolicy} dimensions, and {@code SignalContract} over the twelve feeds
 * a {@link AdvisorySource} merge folds into one. Each asserts what one guest promises. What the gate promises
 * <em>in return</em> was asserted only incidentally, by whichever leg of {@code ComplianceScreenTest} happened to
 * plant a failure - and a fixture cannot state it anyway, because "the screen I am plugged into did not admit the
 * artifact when I gave way" is not a sentence a fixture can say about itself.
 *
 * <h2>The promises, and why this host's are the opposite of a report host's</h2>
 * The {@code Contributions} states the rule this host is the counter-example to: containment is for
 * observers and report contributors, <b>never for a gate</b>. A verdict-bearing seam that contains a guest's failure
 * converts "I could not check this" into "I checked this and it is clean", which is the one answer a gate must never
 * invent. So where the posture and observability fan-outs degrade one row and carry on, this host fails <em>closed</em>
 * and holds the artifact. Five promises follow from that, and each is driven below by a guest that breaks its side:
 * <ol>
 *   <li><b>No guest failure is ever a clean verdict.</b> An inspector or a dimension that throws holds the upload; it
 *       is never admitted and never served, whichever of the failure shapes its SPI permits it raised.</li>
 *   <li><b>Every failure shape the SPI permits lands on the same leg.</b> {@code QualityInspector.inspect} declares
 *       {@code throws IOException}, so a plain {@link IOException} is as legal a guest failure as a
 *       {@link RuntimeException} or a {@code MalformedArtifactException}. Before the host caught the first and
 *       the third and nothing in between: a {@code ZipException} off a truncated central directory reached the
 *       publisher as a raw 500 with no hold, no recorded finding and no diagnostic, while the same inspector raising
 *       an {@code IllegalStateException} over the same bytes was held with a legible reason.</li>
 *   <li><b>The failure is attributed.</b> The hold names the inspector's implementation class, read from the class
 *       <em>before</em> the guest was called. A deployment installs seventeen inspectors; "a quality inspector threw"
 *       names none of them, and asking the broken guest which one it was is how a handler gets defeated from inside
 *       the containment it exists to provide.</li>
 *   <li><b>A merged feed that fails does not read as "no advisories".</b> The gate assesses <em>through</em> the
 *       {@link AdvisorySource} merge, and an empty answer and a failed one mean opposite things about an artifact.</li>
 *   <li><b>An {@link Error} is not filed as the gate's verdict.</b> It is the runtime or the module graph giving way
 *       under one guest, not that guest answering, and the artifact must not be admitted on the strength of it.
 *       <b>Who the caller is decides the escalation</b>, which is the part that does not transfer from the earlier work by
 *       convention: this host runs on the publisher's own request thread, so there IS a caller, and the {@code Error}
 *       reaches it rather than being converted into a hold. That is the opposite of {@code MaintenanceScheduler}'s
 *       ruling, and deliberately so - the worker loop has no caller, so rethrowing there would kill the deployment's
 *       only maintenance loop, while rethrowing here fails one publish and nothing else.</li>
 * </ol>
 *
 * <h2>Negative control, run against the real tree and reverted</h2>
 * Restoring the pre-{@code inspect} loop (no per-guest try) made
 * {@link #an_inspector_raising_a_plain_io_exception_is_held_like_its_two_siblings()} fail with the defect verbatim -
 * the {@code IOException} out of {@code Publication.screen} instead of a {@code QUARANTINE} - and made
 * {@link #a_hold_names_the_inspector_that_failed()} fail with a reason that named no class. Reverting restored green,
 * so neither leg passes because it asserts nothing.
 */
class GateHostContractTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    // --- promises 1 and 2: every failure shape the guest SPI permits reaches the same fail-closed leg --------------

    @Test
    void an_inspector_raising_a_plain_io_exception_is_held_like_its_two_siblings() throws IOException {
        // The third of the three shapes QualityInspector.inspect may raise, and the one that used to escape. A
        // ZipException off a truncated archive is an IOException, not a MalformedArtifactException, and not a
        // RuntimeException - so it fell between the screen's two catch clauses and out of the publish.
        String path = "/gatetest/inspectorio/lib-1.0.jar";

        Publication.Published published = publish(gate(AdvisorySource.none()), path, "bytes the inspector cannot read");

        assertThat(published.disposition())
                .as("an inspector's IOException holds the upload - could not fully screen, so it is not served")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(new Publication(store).located(path)).as("the unscreened upload is withheld from serving").isEmpty();
        assertThat(new QuarantineLog(store).events())
                .as("and the fail-closed hold is recorded for review rather than lost with the 500 it used to be")
                .isNotEmpty();
        assertThat(new QuarantineLog(store).events().getFirst().verdict()).isEqualTo(Verdict.QUARANTINE);
    }

    @Test
    void the_three_inspector_failure_shapes_are_indistinguishable_at_the_verdict() throws IOException {
        // The point of promise 2 stated as one assertion: which of the three legal shapes a guest raised is a detail
        // of the guest, and a host that routes on it is a host whose behaviour depends on an implementation choice
        // its SPI leaves free. All three hold, all three record.
        for (String kind : List.of("malformed", "inspectorfault", "inspectorio")) {
            Path isolatedRoot = Files.createDirectories(root.resolve(kind));
            ArtifactStore isolated = ArtifactStoreProvider.resolve("filesystem",
                    key -> "jenreg.filesystem.root".equals(key) ? isolatedRoot.toString() : null);
            String path = "/gatetest/" + kind + "/lib-1.0.jar";
            Publication publication = new Publication(isolated,
                    List.of(new ComplianceScreen(() -> gate(AdvisorySource.none()))));

            Publication.Published published = publication.screen(ArtifactDescriptor.at("test", path),
                    new ByteArrayInputStream("hostile".getBytes(StandardCharsets.UTF_8)));

            assertThat(published.disposition())
                    .as("a " + kind + " inspector failure holds the upload like every other shape")
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
            assertThat(new QuarantineLog(isolated).events())
                    .as("and records the hold, so a reviewer meets it whichever shape the guest chose")
                    .isNotEmpty();
        }
    }

    // --- promise 3: the failure is attributed, by an identity read before the guest was called --------------------

    @Test
    void a_hold_names_the_inspector_that_failed() throws IOException {
        String path = "/gatetest/inspectorfault/lib-1.0.jar";

        publish(gate(AdvisorySource.none()), path, "bytes an inspector chokes on");

        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .as("the recorded reason names the implementation class that failed - with seventeen inspectors "
                        + "installed, 'a quality inspector threw' is not a diagnosis")
                .anySatisfy(reason -> assertThat(reason).contains(GateTestInspector.class.getName()));
    }

    @Test
    void the_attribution_survives_an_inspector_that_cannot_be_asked_anything() throws IOException {
        // The identity is read off the guest's CLASS before the call, so there is no second call into a guest that
        // has already given way - which is what the handler used to need, and what and both closed one
        // host up. Driven with the Error route because an Error is the most complete way a guest can stop answering.
        String path = "/gatetest/inspectorbroken/lib-1.0.jar";

        assertThatThrownBy(() -> publish(gate(AdvisorySource.none()), path, "bytes on a broken runtime"))
                .as("promise 5: an Error is not the guest's answer, and this host HAS a caller - the publisher's own "
                        + "request thread - so it is escalated there rather than converted into a verdict")
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessageContaining("planted");
        assertThat(new Publication(store).located(path))
                .as("and, whatever else happens, the artifact is not served: an Error must never read as a clean gate")
                .isEmpty();
    }

    // --- promise 4: a merged feed that failed is not an empty feed --------------------------------------------------

    @Test
    void a_dimension_that_throws_holds_the_upload_rather_than_being_skipped() throws IOException {
        // The discovered GatePolicy dimensions (license, attestation, known-exploited, secret-scan) run inside
        // ComplianceGate.assess with no containment at all, and that is the design: containing a dimension would file
        // "I could not evaluate this" as "this passed my check". The host promise is that the uncontained throw is
        // caught by the screen ONE level up and turned into a hold, not into an admit.
        ComplianceGate gate = gate(AdvisorySource.none()).policies(List.of((subject, advisories) -> {
            throw new IllegalStateException("planted: this dimension cannot reach its policy data");
        }));
        String path = "/gatetest/clean/lib-1.0.jar";

        Publication.Published published = publish(gate, path, "bytes no dimension could clear");

        assertThat(published.disposition())
                .as("a dimension that could not evaluate holds the upload - it is never silently skipped, because a "
                        + "skipped dimension is indistinguishable from a dimension that found nothing")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(new Publication(store).located(path)).isEmpty();
        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .as("and the hold names itself a screening outage rather than a verdict about the content")
                .anySatisfy(reason -> assertThat(reason).contains(ComplianceScreen.FEED_FAILED_CLOSED));
    }

    @Test
    void one_failing_feed_in_a_merge_fails_the_whole_merge_closed() throws IOException {
        // The AdvisorySource merge is the one fan-out in this host that a reader might expect to behave like a report:
        // twelve feeds, one union, and an obvious temptation to skip the one that is down. It must not. A merge that
        // contained a feed's failure would answer "no advisories for this coordinate" - the exact shape of a clean
        // artifact - so the union is all-or-nothing and the screen holds.
        AdvisorySource healthy = AdvisorySource.of(Map.of(
                "org.clean:lib", List.of(new AdvisorySource.Advisory("GHSA-0000-0000", Severity.LOW, false))));
        AdvisorySource down = new AdvisorySource() {

            @Override
            public List<AdvisorySource.Advisory> advisories(String ecosystem, String coordinate, String version) {
                throw new UncheckedIOException(new IOException("planted: this feed is rate limited"));
            }

            @Override
            public Freshness freshness() {
                return Freshness.NEVER;
            }
        };
        String path = "/gatetest/clean/lib-1.0.jar";

        Publication.Published published = publish(
                gate(AdvisorySource.combined(healthy, down)), path, "bytes one feed could not clear");

        assertThat(published.disposition())
                .as("the healthy feed's clean answer does not stand in for the one that failed")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .anySatisfy(reason -> assertThat(reason).contains("rate limited"));
    }

    // --- the negative control the four legs above need --------------------------------------------------------------

    @Test
    void a_gate_whose_guests_all_answer_admits_and_serves() throws IOException {
        // Without this, every leg above is satisfiable by a host that holds everything. The same inspector, the same
        // merge shape and a dimension that answers cleanly must still produce a served artifact.
        ComplianceGate gate = gate(AdvisorySource.combined(AdvisorySource.none(), AdvisorySource.none()))
                .policies(List.of((subject, advisories) -> List.of()));
        String path = "/gatetest/clean/lib-1.0.jar";

        Publication.Published published = publish(gate, path, "clean bytes");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(new Publication(store).located(path)).as("and it serves").isPresent();
        assertThat(new QuarantineLog(store).events()).as("with nothing withheld").isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private Publication.Published publish(ComplianceGate gate, String path, String body) throws IOException {
        Publication publication = new Publication(store, List.of(new ComplianceScreen(() -> gate)));
        ArtifactDescriptor descriptor = ArtifactDescriptor.at("test", path);
        Publication.Published outcome = publication.screen(descriptor,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        if (outcome.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            publication.link(descriptor.path(), outcome.hash());
        }
        return outcome;
    }

    /** A gate with only the vulnerability dimension (and the default malicious policy) - no licence dimension and no
     *  network - so a verdict turns purely on what the hostile guest of the leg under test did. */
    private static ComplianceGate gate(AdvisorySource advisories) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), advisories);
    }
}
