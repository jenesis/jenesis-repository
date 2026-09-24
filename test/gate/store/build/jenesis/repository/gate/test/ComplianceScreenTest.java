package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compliance screen's disposition routing over the publication-interceptor chain, driven in isolation through
 * an injected {@link ComplianceGate} and the discovered {@link GateTestInspector}: a clean upload admits and serves; a
 * policy-violating one is rejected (deny-list) or quarantined (malicious package) per the merged inspection verdict; a
 * quarantined upload is recorded in the {@link QuarantineLog} and withheld from serving, not served; the "parsed-empty
 * ⇒ clean" leg is distinguished from the "could-not-parse ⇒ held" leg <em>at the screen</em>; and an un-wired
 * screen gates nothing.
 */
class ComplianceScreenTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_clean_artifact_clears_the_screen_and_serves() throws IOException {
        String path = "/gatetest/clean/lib-1.0.jar";
        Publication.Published published = publish(gate(AdvisorySource.none()), path, "clean bytes");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(gated().located(path)).as("an admitted artifact is served").isPresent();
        assertThat(new QuarantineLog(store).events()).as("nothing withheld").isEmpty();
    }

    @Test
    void a_deny_listed_coordinate_is_rejected_and_never_linked() throws IOException {
        ComplianceGate gate = gate(AdvisorySource.none()).denyList(new DenyListPolicy(List.of("com.deny:*")));
        String path = "/gatetest/deny/pkg-1.0.jar";
        Publication.Published published = publish(gate, path, "denied bytes");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.REJECT);
        assertThat(gated().located(path)).as("a rejected artifact is not served").isEmpty();
        assertThat(gated().blob("/quarantine" + path)).as("a reject holds no bytes for review").isEmpty();
        QuarantineLog.Event event = new QuarantineLog(store).events().getFirst();
        assertThat(event.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(event.coordinate()).isEqualTo("com.deny:pkg:1.0");
        assertThat(event.reasons()).isNotEmpty();
    }

    @Test
    void a_malicious_package_is_quarantined_recorded_and_withheld_not_served() throws IOException {
        // A malicious-package advisory the CVSS threshold would miss (severity NONE), with the malicious dial set
        // to QUARANTINE - routed to a quarantine the screen records and withholds. The dial is named rather than
        // inherited: the shipped default is REJECT, and a rejection holds no bytes, which is the sibling test
        // above. This one is about the hold.
        AdvisorySource advisories = AdvisorySource.of(Map.of(
                "com.mal:stealer", List.of(new AdvisorySource.Advisory("MAL-2026-0001", Severity.NONE, true))));
        String path = "/gatetest/malicious/stealer-1.0.jar";

        Publication.Published published = publish(gate(advisories), path, "stealer bytes");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        // Recorded, not served: the review surface can explain the hold, and the path does not serve.
        QuarantineLog.Event event = new QuarantineLog(store).events().getFirst();
        assertThat(event.verdict()).isEqualTo(Verdict.QUARANTINE);
        assertThat(event.coordinate()).isEqualTo("com.mal:stealer:1.0");
        assertThat(gated().located(path)).as("a quarantined path is withheld from serving").isEmpty();
        assertThat(gated().blob("/quarantine" + path)).as("the held bytes are kept for review").isPresent();
        assertThat(Publication.reviewPending(store, path))
                .as("the review pointer stands - the queue's answer, which the serving pointer copies for a serve")
                .isTrue();
    }

    @Test
    void a_claimed_artifact_that_parsed_empty_is_clean_but_one_that_could_not_parse_is_held() throws IOException {
        // at the screen: "parsed fine, declares nothing" is NOT the same as "could not parse". The first admits
        // (empty ⇒ clean); the second fails closed into quarantine with a legible reason (could-not-parse ⇒ not-clean).
        Publication.Published empty = publish(gate(AdvisorySource.none()), "/gatetest/empty/nothing-1.0.jar", "nothing");
        assertThat(empty.disposition())
                .as("a claimed artifact that parsed cleanly and declared nothing is admitted")
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(new QuarantineLog(store).events()).isEmpty();

        Publication.Published malformed = publish(gate(AdvisorySource.none()),
                "/gatetest/malformed/corrupt-1.0.jar", "corrupt");
        assertThat(malformed.disposition())
                .as("a claimed artifact the inspector could not parse is held, never a silent clean")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        QuarantineLog.Event event = new QuarantineLog(store).events().getFirst();
        assertThat(event.verdict()).isEqualTo(Verdict.QUARANTINE);
        assertThat(event.reasons())
                .as("the hold names why it could not be fully screened")
                .anySatisfy(reason -> assertThat(reason).contains("Could not fully screen"));
    }

    @Test
    void a_feed_that_fails_closed_holds_the_publish_rather_than_admitting_or_letting_the_error_escape()
            throws IOException {
        // An advisory feed that fails closed throws UncheckedIOException rather than reporting an
        // empty "clean" answer - AdvisoryFeedFailClosedTest proves the real OSV/GitHub/malicious/KEV feeds do exactly
        // this on a non-200 (a rate limit, a mirror outage). The gate assesses THROUGH the feed, so its assess raises;
        // the screen must fail closed - HOLD the upload (could not fully screen ⇒ do not serve), never admit the
        // unscreened bytes as a silent clean and never let a raw error escape to the publisher as a 500.
        AdvisorySource failing = new AdvisorySource() {

            @Override
            public List<AdvisorySource.Advisory> advisories(String ecosystem, String coordinate, String version) {
                throw new UncheckedIOException(new IOException("advisory feed unreachable (rate limited)"));
            }

            @Override
            public Freshness freshness() {
                return Freshness.NEVER;
            }
        };
        String path = "/gatetest/clean/lib-1.0.jar";

        Publication.Published published = publish(gate(failing), path, "bytes the feed could not clear");

        assertThat(published.disposition())
                .as("a feed that fails closed holds the publish - not a silent ACCEPT of unscreened bytes")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(gated().located(path)).as("the un-screened upload is withheld from serving").isEmpty();
        assertThat(Publication.reviewPending(store, path))
                .as("the review pointer stands - the queue's answer, which the serving pointer copies for a serve")
                .isTrue();
        assertThat(new QuarantineLog(store).events())
                .as("the fail-closed hold is recorded for review, not dropped").isNotEmpty();
        assertThat(new QuarantineLog(store).events().getFirst().verdict()).isEqualTo(Verdict.QUARANTINE);
        // The hold says WHICH kind of hold it is, in a form a reader can key on. A screening outage and a
        // policy verdict both arrive as QUARANTINE, and telling them apart is what lets an end-to-end scenario skip
        // on an unreachable feed instead of reporting a product failure - so the wording is a published constant and
        // this is the assertion that keeps the two ends from drifting apart.
        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .as("the hold names itself a screening outage to retry, not a verdict about the content")
                .anySatisfy(reason -> assertThat(reason)
                        .contains(ComplianceScreen.FEED_FAILED_CLOSED)
                        .contains("advisory feed unreachable (rate limited)"));
        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .as("a policy hold must not be mistakable for one: the deny-listed and malicious legs above carry no "
                        + "such marker, which is what makes matching on it a safe skip criterion")
                .isNotEmpty();
    }

    /**
     * a hold's <em>reasons</em> are filed where its <em>handle</em> is, even when the screened descriptor is a
     * push endpoint that names no coordinate.
     *
     * <p>A format whose coordinate lives inside the artifact commits under the only descriptor it can build before
     * the bytes are down - {@code /nuget/v3/package} is the real one, one path every push of that format shares -
     * and re-keys the {@code /quarantine} review handle onto the package once the {@code .nuspec} is readable. The
     * gate used to file the audit row and the held-subject record at the endpoint regardless, so a reviewer's handle
     * and their reasons named different paths for the same hold, {@code QuarantineLog.latest} answered nothing for
     * the handle the queue is keyed off, and {@code HoldLifecycle}'s path-keyed reads answered about neither.
     *
     * <p>The screen now asks the installed layout where the coordinate an inspector <em>did</em> read will be served,
     * through the store-free derivation - the only form available before any layout has run. Driven here through a
     * generic test layout rather than through NuGet, because the behaviour being pinned is the gate's.
     */
    @Test
    void a_hold_screened_under_a_shared_push_endpoint_files_its_reasons_where_the_artifact_will_be() throws IOException {
        ComplianceGate gate = gate(AdvisorySource.none())
                .denyList(new DenyListPolicy(List.of("com.endpoint:*")).action(Verdict.QUARANTINE));

        Publication.Published published = publish(gate, GateEndpointTestFormat.ENDPOINT, "bytes under an endpoint");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        String served = "/gatetest/served/com.endpoint:pkg/2.0";
        QuarantineLog.Event event = new QuarantineLog(store).events().getFirst();
        assertThat(event.path())
                .as("the audit row is filed at the path the artifact will be reviewable at, not at the push endpoint "
                        + "every push of this format shares")
                .isEqualTo(served);
        assertThat(new QuarantineLog(store).latest(served))
                .as("so the per-path index - which the review queue reads to put reasons beside a handle - answers")
                .isPresent();
        assertThat(new QuarantineLog(store).latest(GateEndpointTestFormat.ENDPOINT))
                .as("and nothing is left filed under the endpoint, which would be a second row for the same hold")
                .isEmpty();
        assertThat(HeldSubjects.read(store, served))
                .as("the held-subject record follows the row, so a later path-keyed question about the handle answers")
                .isPresent();
    }

    /**
     * The other direction, and the reason the re-key is conditional: a descriptor that already names its coordinate
     * IS the artifact's own path, nothing re-keys it, and the gate must leave it exactly where it was. Without this
     * the leg above could be satisfied by a screen that invents a path for every hold.
     */
    @Test
    void a_hold_screened_under_the_artifacts_own_path_stays_filed_there() throws IOException {
        ComplianceGate gate = gate(AdvisorySource.none()).denyList(new DenyListPolicy(List.of("com.deny:*")));
        String path = "/gatetest/deny/pkg-1.0.jar";

        publish(gate, path, "denied bytes");

        assertThat(new QuarantineLog(store).events().getFirst().path())
                .as("an ordinary format's screened path is the artifact's path and is not re-keyed")
                .isEqualTo(path);
    }

    @Test
    void a_policy_hold_carries_no_screening_outage_marker() throws IOException {
        // The negative control for the leg above: matching on FEED_FAILED_CLOSED must not catch an ordinary verdict,
        // or a scenario would skip over the very policy behaviour it exists to assert.
        AdvisorySource advisories = AdvisorySource.of(Map.of(
                "com.mal:stealer", List.of(new AdvisorySource.Advisory("MAL-2026-0002", Severity.NONE, true))));
        publish(gate(advisories), "/gatetest/malicious/stealer-1.0.jar", "stealer bytes");

        assertThat(new QuarantineLog(store).events().getFirst().reasons())
                .as("a malicious-package hold is a verdict about the content, never a screening outage")
                .isNotEmpty()
                .allSatisfy(reason -> assertThat(reason).doesNotContain(ComplianceScreen.FEED_FAILED_CLOSED));
    }

    @Test
    void an_inspector_that_throws_a_non_malformed_error_holds_the_publish_rather_than_letting_it_escape()
            throws IOException {
        // A quality inspector that throws a NON-MalformedArtifactException runtime error (an unhandled edge over hostile
        // content - an NPE, an index fault) must fail closed exactly as a MalformedArtifactException does: the gate
        // never saw a derived coordinate, so the screen HOLDS the upload (could not fully screen => do not serve),
        // never letting the raw error escape to the publisher as a 500 and never admitting the unscreened bytes.
        String path = "/gatetest/inspectorfault/lib-1.0.jar";

        Publication.Published published = publish(gate(AdvisorySource.none()), path, "bytes an inspector chokes on");

        assertThat(published.disposition())
                .as("an inspector runtime fault holds the publish - not a silent ACCEPT and not an escaped error")
                .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(gated().located(path)).as("the un-screened upload is withheld from serving").isEmpty();
        assertThat(new QuarantineLog(store).events())
                .as("the fail-closed hold is recorded for review").isNotEmpty();
        assertThat(new QuarantineLog(store).events().getFirst().verdict()).isEqualTo(Verdict.QUARANTINE);
    }

    /**
     *, fourth of the four: the anti-laundering guard at the accepted-publish leg. An accepted upload supersedes
     * a stale <em>publish-time</em> hold at its path and clears the {@code /quarantine} pointer - but never one a
     * retroactive sweep owns, or an ordinary re-upload would launder away a human-review hold. That ownership question
     * is asked of the durable {@code holds/} records, and reaching them needs the request path resolved to a
     * coordinate, which needs the owning FORMAT installed - the one dependence left standing and left
     * deliberately open.
     *
     * <p>{@code npm} is genuinely off this test module's graph, so the path resolves to nothing and every record read
     * has no key to look under. Before this the answer degraded to "no sweep owns it" and the leg deleted the
     * {@code /quarantine} pointer - the review queue's only index of the hold - on an upload to that path. The
     * question here is not "is anything held" but "may this screen clear a pointer it may not own", and unresolvable
     * is not ownership.
     */
    @Test
    void an_accepted_publish_never_retires_a_hold_it_cannot_prove_it_owns() throws IOException {
        String path = "/npm/left-pad/-/left-pad-1.0.0.tgz";
        // What a retroactive sweep left behind while the npm module was still installed: the record first, then the
        // review pointer - the ordering every enforce sweep writes in.
        store.write(HoldRecords.key("kev", "npm", "left-pad", "1.0.0"),
                new ByteArrayInputStream("CVE-2021-44228".getBytes(StandardCharsets.UTF_8)));
        Publication publication = gated();
        publication.link("/quarantine" + path, publication.storeBlob(
                new ByteArrayInputStream("the swept body".getBytes(StandardCharsets.UTF_8))));

        Publication.Published published = publish(gate(AdvisorySource.none()), path, "a clean re-upload");

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(publication.blob("/quarantine" + path))
                .as("the sweep's hold is not laundered by an upload to a path nothing can place").isPresent();
    }

    @Test
    void an_unwired_screen_gates_nothing() throws IOException {
        // The ServiceLoader constructor with no live gate wired: inert, so a bare module-path presence never gates -
        // even an upload a wired gate would reject.
        Publication publication = new Publication(store, List.of(new ComplianceScreen()));
        Publication.Published published = publish(publication, 
                ArtifactDescriptor.at("test", "/gatetest/deny/pkg-2.0.jar"),
                new ByteArrayInputStream("denied bytes".getBytes(StandardCharsets.UTF_8)));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
    }

    private Publication gated() {
        return new Publication(store);
    }

    private Publication.Published publish(ComplianceGate gate, String path, String body) throws IOException {
        return publish(new Publication(store, List.of(new ComplianceScreen(() -> gate))),
                ArtifactDescriptor.at("test", path),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** A gate with only the vulnerability dimension (and the default malicious policy) - no licence dimension, no
     *  network - so routing turns purely on the deny-list and the injected advisory source. */
    private static ComplianceGate gate(AdvisorySource advisories) {
        return gate(advisories, Verdict.QUARANTINE);
    }

    /** The same, with the malicious dimension's verdict named - never left to a default, since the one this class
     *  used to inherit was not the one a deployment runs. */
    private static ComplianceGate gate(AdvisorySource advisories, Verdict malicious) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), advisories)
                .malicious(new MaliciousPackagePolicy().action(malicious));
    }

    /** The screen + layout the removed {@code Publication.publish} combined (EPIC 26 /): 0.5.0 split
     *  screening from the accepted write, so this seam screens the upload and, on {@code ACCEPT}, links the serving
     *  pointer - exactly what a single-body ingress deploy does at the edge. */
    private static Publication.Published publish(Publication publication, ArtifactDescriptor descriptor,
                                                 InputStream content) throws IOException {
        Publication.Published outcome = publication.screen(descriptor, content);
        if (outcome.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            publication.link(descriptor.path(), outcome.hash());
        }
        return outcome;
    }
}
