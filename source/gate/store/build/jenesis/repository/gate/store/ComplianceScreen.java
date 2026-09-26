package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.gate.InspectionMerge;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.Maintainer;
import build.jenesis.repository.compliance.Maintainers;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.findings.AdvisoryFindings;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.compliance.TrustAware;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.Recording;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.HoldRecords;

/**
 * The compliance gate riding the publication-interceptor chain. On every gated publish the screen turns
 * the just-stored artifact into compliance subjects through the {@link QualityInspector} that claims its path
 * (reading the content back from the store - only a claimed artifact is ever materialised, and it is a metadata
 * document or a small archive by the inspectors' nature), has the {@link ComplianceGate} assess them, and answers the
 * verdict as the publication's disposition. Once the collective outcome is routed, {@link #committed} records it: an
 * accepted publish lands in the repository inventory (and retires a stale quarantine hold at the same path - the gate
 * just cleared a fresh upload there); a quarantined or rejected one is appended to the {@link QuarantineLog} with the
 * reasons, so the review surface can explain what was withheld and why. The read side, {@link #withheld}, is the
 * quarantine hold itself: a path with a pending {@code /quarantine} pointer does not serve - even a previously linked
 * artifact stays retracted until the hold is released or discarded - at the cost of one pointer read per serve.
 *
 * <p>The screen is discovered by {@code ServiceLoader} and so constructed without context; the deployment wires the
 * live gate at boot through {@link #live}, and until then the screen is inert (every upload is accepted, the withhold
 * read side stays active since it is store truth, not policy). An embedder or test injects the gate directly through
 * the explicit constructor instead of the JVM-wide wiring.
 */
public final class ComplianceScreen implements PublishInterceptor {

    /** The JVM-wide live gate the ServiceLoader-constructed screen reads, set by the deployment at boot. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComplianceScreen.class);

    private static final AtomicReference<Supplier<ComplianceGate>> LIVE = new AtomicReference<>();

    /** A JVM-wide sink the deployment wires so every committed verdict is counted ({@code jenreg.gate.verdicts}),
     *  across EVERY publish path (the deploy, staging, batch) - not just the deploy controller's own
     *  observation. Registry-free: the sink is a plain callback and the Micrometer counter lives in the distribution,
     *  so the gate module stays free of any metrics dependency. */
    private static final AtomicReference<VerdictListener> VERDICTS = new AtomicReference<>();

    /** The deployment's named advisory feeds - the SAME instances the live gate assesses through - re-queried per feed
     *  at commit so a just-accepted coordinate's advisory findings are persisted immediately (closing the window
     *  between its publish and the next scheduled sweep) rather than rendering clean until the sweep catches up. Since
     *  the gate's assess just populated each feed's cache, the commit-time re-query is a warm {@code FeedCache} read,
     *  not a fresh upstream pass. Restart-bound like the feeds themselves, so a plain supplier of the boot-built map;
     *  unset (the ServiceLoader-constructed screen until the deployment wires it) leaves the screen writing no advisory
     *  rows, exactly as before. */
    private static final AtomicReference<Supplier<SequencedMap<String, AdvisorySource>>> FEEDS = new AtomicReference<>();

    /** A registry-free sink the deployment wires so a commit-time feed re-query the warm cache could not answer (a
     *  feed whose refresh failed with nothing cached, failing closed) is counted rather than silently dropped
     *  - the publish is never failed for it. Registry-free like {@link #VERDICTS}: the Micrometer counter lives in the
     *  distribution, so the gate module stays free of any metrics dependency. */
    private static final AtomicReference<FeedMissListener> FEED_MISSES = new AtomicReference<>();

    /** A registry-free sink the deployment wires so an artifact an inspector could not parse is counted
     *  ({@code jenreg.gate.unparseable} tagged by format) rather than only logged - the §9 make-errors-visible
     *  diagnostic. Registry-free like {@link #VERDICTS}: the Micrometer counter lives in the distribution. */
    private static final AtomicReference<UnparseableListener> UNPARSEABLE_METER = new AtomicReference<>();

    /** Whether a blobs-namespace format that resolves no reverse hold mapping for the publish it just laid out
     *  {@code throws} (failing the publish) or only alarms. The deployment wires this from
     *  {@code jenreg.strict-hold-mapping} (default false): production stays alarm-not-abort so one broken
     *  format cannot DoS publishes (the #204 gauge reasoning), while every test that publishes through a format flips
     *  it on so a broken mapping fails on the FIRST publish in CI rather than surfacing in a later audit. Unset (the
     *  ServiceLoader-constructed screen until the deployment wires it) reads as {@code false}. */
    private static final AtomicReference<BooleanSupplier> STRICT_HOLD_MAPPING = new AtomicReference<>();

    /** A registry-free sink the deployment wires so a publish whose blobs-namespace reverse mapping does not resolve
     *  the artifact just served is counted ({@code jenreg.publish.holdmapping.broken} tagged by ecosystem) - a
     *  wiring-regression alarm, the publish-time sibling of the sweep's {@code jenreg.vulnerabilities.hold.unenforceable}
     *  gauge. Registry-free like {@link #VERDICTS}: the Micrometer meter lives in the distribution, so the gate module
     *  stays free of any metrics dependency. */
    private static final AtomicReference<HoldMappingBrokenListener> HOLD_MAPPING_BROKEN = new AtomicReference<>();

    private static final List<QualityInspector> INSPECTORS = QualityInspector.all();

    /** The findings ledger, when a persistence module is installed - the structured sibling of the quarantine
     *  log's flat reason line; empty leaves the screen's behaviour exactly as before. */
    private static final Optional<FindingsProvider> FINDINGS = FindingsProvider.installed();

    /** The durable maintainer-health ledger, when a persistence module is installed. Present, it is overlaid onto the
     *  gate before assess (so the health dimension scores off the persisted answer, not a live probe - Principle 10) and
     *  written at commit for a just-accepted coordinate; absent, the gate keeps its live health source and the screen
     *  persists no health, exactly as before. */
    /** The deployment's signer trust, when a module supplies one. Overlaid onto every {@link TrustAware} inspector
     *  before it runs, for the reason the health ledger is overlaid onto its dimension: the screen holds the
     *  request's scoped store and the {@link java.util.ServiceLoader}-built inspector does not. Absent, an inspector
     *  verifies against nothing and reports every signature untrusted - the fail-closed direction, and never a silent
     *  "trusted" because a module is missing. */
    private static final boolean TRUST_INSTALLED = !SignerTrustProvider.installed().isEmpty();

    /** The deployment's effective per-tenant configuration, wired by the composition root - the same lookup the gate's
     *  dimensions are built from. Trust is configuration, and a screen that read it anywhere else would answer about
     *  a different deployment than the one the gate was built for. */

    private static final Optional<HealthLedgerProvider> HEALTH = HealthLedgerProvider.installed();

    /** The deployment's live maintainer-health source - the SAME instance the sweep probes through - consulted per
     *  accepted coordinate at commit so a just-published coordinate carries its health in the ledger immediately
     *  (closing the window between its publish and the next scheduled health sweep, so admission of a LATER version of
     *  the same coordinate reads a populated ledger). Restart-bound like the source itself, so a plain supplier; unset
     *  (the ServiceLoader-constructed screen until the deployment wires it) leaves the screen writing no health record,
     *  and the sweep populates the coordinate on its next pass. */
    private static final AtomicReference<Supplier<HealthSource>> HEALTH_SOURCE = new AtomicReference<>();

    /** The most bytes handed to an inspector from a claimed artifact. A claimed upload is a metadata document or a
     *  small archive by the inspectors' nature (a POM, a {@code package.json}, a {@code .nuspec}), whose declaration
     *  sits at the front - a jar's manifest, a wheel's METADATA - so a bounded prefix carries everything they read,
     *  while a pathologically large jar can no longer be pulled whole into a {@code byte[]} to gate it (which a
     *  {@code readAllBytes} would, materialising the entire artifact in heap on the publish path). Beyond the cap the
     *  archive is truncated, and the zip/tar reader inside the inspector simply stops at the last complete entry.
     *  <p>It is the SPI's prefix tier itself, not a screen-local copy of the same number: the {@code byte[]} legs are
     *  contractually handed at most that much, and {@link #inspect} decides whether the inspectors saw the artifact
     *  whole by comparing the body against this very bound - so a screen reading a different amount than the tier the
     *  inspectors are written against would make that completeness test answer about a different body. */
    /** Read through the accessor, never latched: the tier is an operator dial, and a screen holding a stale
     *  copy of it would disagree with the inspectors it is comparing against about what "whole" means. */
    private static int inspectionLimit() {
        return QualityInspector.prefixInspectionLimit();
    }

    /**
     * The {@code source} every gate finding this screen writes is attributed to. Named here, once, because a console
     * resolving a recorded source back to its writer has to be able to ask this module for the string rather than
     * carry a copy of a literal that lives in a method body.
     *
     * <p>It names <b>the screen, not the plug-in</b>, and deliberately so: a gate decision is assembled from every
     * discovered {@code GatePolicyProvider} dimension and written as one row per reason, and a finding carries the
     * <em>hold kind</em> it warrants ({@code ComplianceGate.Finding.hold()}, for the dimensions whose sweeps also hold)
     * but not a per-policy source name; attributing rows per policy would re-identify every stored gate row. The
     * consequence a console must respect is precise: this name is <em>installed</em> wherever this module is, so it
     * resolves to a mark of its own and never to an orphan - reading "the gate is gone" off a row the gate itself
     * wrote would be a plain lie.
     */
    public static final String GATE_SOURCE = "gate";

    /**
     * The {@code source} every quality-inspection failure this screen records is attributed to - the same shape, and
     * the same limit, as {@link #GATE_SOURCE}. {@code QualityInspector} declares no name at all (its own Contract
     * says inspectors are additive and never selected by name), so there is no per-inspector identity to record even
     * in principle; what the row can honestly say is that the inspection stage could not parse the artifact.
     */
    public static final String INSPECTION_SOURCE = "inspection";

    /** Set on the releasing thread while {@link HoldLifecycle#release} replays a held upload's format dispatch. The
     *  operator has already reviewed and released the hold, so a format whose own {@code handle} re-publishes through
     *  the {@code Publication} (Maven's does, npm's raw {@code blobs} writes do not) must not be re-screened into
     *  a fresh quarantine of the very bytes just released - the release IS the override of that verdict. While set the
     *  screen accepts unconditionally; the withhold read side ({@link #withheld}) is untouched, since it is store truth
     *  not policy. Thread-scoped and cleared in a {@code finally} by the replay, so it can never leak past it. */
    private static final ThreadLocal<Boolean> RELEASING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Supplier<ComplianceGate> gate;

    /** What this screen assessed, stashed between {@link #assess} and {@link #committed} - both run on the
     *  publishing thread within one {@code Publication} call, so the audit can name the subjects and reasons. */
    private final ThreadLocal<ComplianceGate.Assessment> assessed = new ThreadLocal<>();
    private final ThreadLocal<List<ComplianceGate.Subject>> subjects = new ThreadLocal<>();

    /** The reason an inspector could not parse this upload, stashed between {@link #assess} and {@link #committed} on
     *  the publishing thread so the commit records the distinct inspection-failed finding (and names the reason in the
     *  quarantine log when the artifact is held). Null when the upload parsed - the ordinary case. */
    private final ThreadLocal<String> unparseable = new ThreadLocal<>();

    /** Whether the reason stashed above is an oversize refusal rather than a parse failure - the two are
     *  recorded under different codes, because a ledger that filed them together could not tell an artifact
     *  nobody could read from one nobody was asked to. Set beside the reason and cleared with it. */
    private final ThreadLocal<Boolean> oversizedFinding = new ThreadLocal<>();

    /** The reason an advisory feed failed closed while assessing this upload (a rate limit, a mirror outage - the feed
     *  throws rather than reporting a clean answer), stashed between {@link #assess} and {@link #committed} on the
     *  publishing thread so the fail-closed hold names why the screen could not complete. Null when assessment ran to a
     *  verdict - the ordinary case. */
    private final ThreadLocal<String> feedFailure = new ThreadLocal<>();

    /**
     * The wording a fail-closed feed's hold reason carries, so a reader can tell a <em>screening outage to retry</em>
     * from a policy verdict about the content. Public and named because it is read back: a hold recorded under it is
     * the environment failing, not the artifact, and a caller that must distinguish the two - an end-to-end scenario
     * deciding whether an unreachable feed should skip it rather than red it - matches on this constant
     * instead of keeping its own copy of the sentence, so a reworded reason moves both ends at once.
     */
    public static final String FEED_FAILED_CLOSED = "an advisory feed failed closed";

    /** The {@code ServiceLoader} constructor: the gate is whatever the deployment wired through {@link #live},
     *  or nothing (an inert screen) until it does. */
    public ComplianceScreen() {
        this.gate = () -> {
            Supplier<ComplianceGate> live = LIVE.get();
            return live == null ? null : live.get();
        };
    }

    /** An explicitly configured screen - the seam a test or an embedder uses instead of the JVM-wide wiring. */
    public ComplianceScreen(Supplier<ComplianceGate> gate) {
        this.gate = gate;
    }

    /** Wire the deployment's live gate into every discovered screen in this JVM; closing the returned wiring
     *  unwires it again (only if it is still the current one), so a booted-then-closed server never leaves a stale
     *  gate behind for the next context in the same JVM. */
    public static Wiring live(Supplier<ComplianceGate> gate) {
        LIVE.set(gate);
        return new Wiring(gate);
    }

    /** A registry-free callback the deployment wires so a committed verdict is counted: {@code (format, verdict)}
     *  where verdict is the {@link Disposition} name. */
    @FunctionalInterface
    public interface VerdictListener {
        void recorded(String format, String verdict);
    }

    /** Wire the deployment's verdict sink into every discovered screen in this JVM; closing the returned handle
     *  retires it (only if it is still the current one). */
    public static AutoCloseable verdicts(VerdictListener listener) {
        VERDICTS.set(listener);
        return () -> VERDICTS.compareAndSet(listener, null);
    }

    /** The lookup this screen resolves its per-request dials through - {@link ComplianceSettings#lookup()}, which is
     *  where it lives, because the proxy path and two observers outside this module have to read the same one. */
    private static UnaryOperator<String> configuration() {
        return ComplianceSettings.lookup();
    }

    /** Wire the deployment's named advisory feeds - the same instances the live gate assesses through - into every
     *  discovered screen in this JVM, so a committed publish re-queries them (a warm {@code FeedCache} read) and
     *  persists the just-published coordinate's advisory findings at once. Closing the returned handle retires the
     *  wiring (only if it is still the current one), so a booted-then-closed server leaves no stale feeds behind. */
    public static AutoCloseable advisoryFeeds(Supplier<SequencedMap<String, AdvisorySource>> feeds) {
        FEEDS.set(feeds);
        return () -> FEEDS.compareAndSet(feeds, null);
    }

    /** Wire the deployment's live maintainer-health source - the same instance the health sweep probes through - into
     *  every discovered screen in this JVM, so a committed publish persists the just-accepted coordinate's health into
     *  the durable ledger at once. Closing the returned handle retires the wiring (only if it is still the current one),
     *  so a booted-then-closed server leaves no stale source behind. */
    public static AutoCloseable healthSource(Supplier<HealthSource> source) {
        HEALTH_SOURCE.set(source);
        return () -> HEALTH_SOURCE.compareAndSet(source, null);
    }

    /** A registry-free callback the deployment wires so a commit-time feed re-query that failed closed (the warm cache
     *  had nothing and the refresh could not reach the feed) is counted: {@code (feed)} names the feed that missed. */
    @FunctionalInterface
    public interface FeedMissListener {
        void missed(String feed);
    }

    /** Wire the deployment's feed-miss sink into every discovered screen in this JVM; closing the returned handle
     *  retires it (only if it is still the current one). */
    public static AutoCloseable advisoryFeedMisses(FeedMissListener listener) {
        FEED_MISSES.set(listener);
        return () -> FEED_MISSES.compareAndSet(listener, null);
    }

    /** A registry-free callback the deployment wires so an artifact an inspector could not parse is counted:
     *  {@code (format)} names the ecosystem/format of the unparseable upload. */
    @FunctionalInterface
    public interface UnparseableListener {
        void detected(String format);
    }

    /** Wire the deployment's unparseable-artifact meter into every discovered screen in this JVM; closing the returned
     *  handle retires it (only if it is still the current one). */
    public static AutoCloseable unparseableArtifacts(UnparseableListener listener) {
        UNPARSEABLE_METER.set(listener);
        return () -> UNPARSEABLE_METER.compareAndSet(listener, null);
    }

    /** Wire whether the publish-time hold-mapping round-trip check ({@link #onPublished}) throws on a break: the
     *  deployment supplies {@code jenreg.strict-hold-mapping} through {@code LiveConfig}, so the value is
     *  read from the effective settings the same way the gate dials are (default false in production, on in the test
     *  config). Closing the returned handle retires the wiring (only if it is still the current one). */
    public static AutoCloseable strictHoldMapping(BooleanSupplier strict) {
        STRICT_HOLD_MAPPING.set(strict);
        return () -> STRICT_HOLD_MAPPING.compareAndSet(strict, null);
    }

    /** A registry-free callback the deployment wires so a broken publish-time hold mapping is counted: {@code (eco)}
     *  names the ecosystem whose blobs-namespace format resolved no served path or content hash for the artifact it
     *  just laid out. */
    @FunctionalInterface
    public interface HoldMappingBrokenListener {
        void broken(String ecosystem);
    }

    /** Wire the deployment's broken-hold-mapping meter into every discovered screen in this JVM; closing the returned
     *  handle retires it (only if it is still the current one). */
    public static AutoCloseable holdMappingBroken(HoldMappingBrokenListener listener) {
        HOLD_MAPPING_BROKEN.set(listener);
        return () -> HOLD_MAPPING_BROKEN.compareAndSet(listener, null);
    }

    /** The handle {@link #live} returns; closing it retires that wiring. */
    public static final class Wiring implements AutoCloseable, build.jenesis.repository.store.PublishPathWiring {

        private final Supplier<ComplianceGate> gate;

        private Wiring(Supplier<ComplianceGate> gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            LIVE.compareAndSet(gate, null);
        }
    }

    /** Run {@code dispatch} with the screen suppressed on this thread, so a format whose {@code handle} re-publishes
     *  through the {@code Publication} does not re-quarantine an already-released upload during a review release
     *  replay. Cleared in a {@code finally}, so a throwing dispatch never leaves the flag set. */
    static void replaying(Replay dispatch) throws IOException {
        RELEASING.set(Boolean.TRUE);
        try {
            dispatch.run();
        } finally {
            RELEASING.remove();
        }
    }

    /** A format-dispatch replay {@link #replaying} runs with the screen suppressed. */
    @FunctionalInterface
    interface Replay {
        void run() throws IOException;
    }

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        assessed.remove();
        subjects.remove();
        unparseable.remove();
        oversizedFinding.remove();
        feedFailure.remove();
        if (RELEASING.get()) {
            // A review release is replaying this upload's own dispatch: the hold was already reviewed and released, so
            // accept the re-publish rather than re-screening the released bytes into a fresh quarantine.
            return Disposition.ACCEPT;
        }
        ComplianceGate current = gate.get();
        if (current == null) {
            return Disposition.ACCEPT;
        }
        List<ComplianceGate.Subject> inspected;
        try {
            inspected = inspect(artifact, content);
        } catch (OversizedArtifactException oversized) {
            // The artifact is past the inspection prefix and this deployment has said not to stream it.
            // Unlike the two legs below this is not a failure of anything - nothing was attempted - so
            // the reason recorded is about the artifact's SIZE and claims nothing about its contents.
            return screenOversized(artifact, oversized);
        } catch (MalformedArtifactException malformed) {
            // An inspector claimed this artifact but could not parse it (truncated, corrupt, a decompression bomb).
            // That is distinct from "parsed fine, declares nothing": it must never read as a silent clean. The gate's
            // input silently failed to derive, so the license/vulnerability dimensions never saw the real coordinate -
            // fail closed and HOLD it (could not fully screen ⇒ do not serve), recording a scoped, legible reason.
            return screenMalformed(artifact, malformed);
        } catch (RuntimeException failure) {
            // An inspector threw a NON-Malformed runtime error parsing this upload - an unhandled edge over hostile
            // content (an NPE, an index/arithmetic fault) that was not wrapped as a MalformedArtifactException. The gate
            // never saw a derived coordinate, exactly as for the malformed case, so it fails closed and HOLDS it (could
            // not fully screen ⇒ do not serve) rather than letting the raw error escape to the publisher as a 500 or
            // admitting the unscreened bytes - the same discipline screenMalformed and screenFeedFailure apply.
            return screenInspectorFailure(artifact, failure);
        }
        if (inspected.isEmpty()) {
            // Nothing derived a package subject, so no parsed coordinate ever reached the gate - screen the path's
            // own coordinate against the operator deny-list and the feeds rather than admitting the bytes unscreened.
            //
            // Whether an inspector CLAIMED the path is deliberately no part of this, and reading it as one was a
            // fail-open. A claim is a declaration of interest, not an assessment: an inspector claims a path it may
            // have something to say about, and one that then derives nothing has screened exactly as much as an
            // inspector that never looked. Taking "claimed and found nothing" for "already screened" is how an Ivy
            // jar published with a CRITICAL advisory against its own coordinate - the signature inspector claims it,
            // because the layout declares a sidecar convention and describes a coordinate, and derives no subject
            // when the publisher signed nothing, while no layout inspector reads /ivy at all. The only screening that
            // ran on that publish was on the jar's own .sha1, which nothing claims - and that checksum was REJECTED,
            // on the very advisory the jar it names carried.
            return screenFromPath(artifact, current);
        }
        if (InspectionMerge.noPackageSubject(inspected)) {
            // Content findings and no package subject - a Sigstore bundle beside a file nothing parsed as a package.
            // The file is still screened from its path against the deny-list, and what was found beside it is
            // assessed with it: a bundle beside a deny-listed name neither switches the deny-list off nor goes
            // unexamined.
            return screenFromPath(artifact, overlaid(current, content.store(), inspected, artifact.path()), inspected);
        }
        current = overlaid(current, content.store(), inspected, artifact.path());
        ComplianceGate.Assessment assessment;
        try {
            assessment = current.assess(inspected);
        } catch (RuntimeException failure) {
            // An advisory feed (license/vulnerability/health) failed closed while assessing this upload: a real feed
            // throws UncheckedIOException on a non-200 (a rate limit, a mirror outage) rather than reporting an empty
            // "clean" answer, and the gate assesses THROUGH the feed, so its assess raises here. Could-not-fully-screen
            // fails closed exactly as an unparseable body does - HOLD the upload in quarantine with a legible reason,
            // never admit the unscreened bytes as a silent clean and never let the raw error escape to the publisher as
            // a 500.
            return screenFeedFailure(artifact, inspected, failure);
        }
        assessed.set(assessment);
        subjects.set(inspected);
        return switch (assessment.verdict()) {
            case ALLOW -> Disposition.ACCEPT;
            case QUARANTINE -> Disposition.QUARANTINE;
            case REJECT -> Disposition.REJECT;
        };
    }

    /**
     * The gate this repository actually assesses through: the deployment's gate with its two per-repository overlays
     * applied. Extracted rather than inlined because a publish is no longer the only caller - the late-declaration
     * re-assessment in {@link #releaseCompleted} assesses held bytes through the very same gate, and a re-assessment
     * running a differently-configured gate than the publish did would report a difference that is an artefact of the
     * caller rather than of the evidence.
     *
     * <p><b>Waivers.</b> This repository's recorded accept-risk waivers, scoped to just the coordinates being
     * assessed. Findings (and the waiver annotations on them) are per-repository, so the overlay reads THIS
     * publish's own scoped store - the doubly-scoped tenant/repository space; a read failure degrades to no
     * suppression (fail toward screening), and an absent findings module leaves the gate's own {@code Waivers.NONE}
     * in place. Coordinate-scoped (a point lookup per inspected subject) so a publish into a busy repository never
     * walks the whole findings ledger to build the overlay.
     *
     * <p><b>Health.</b> The health dimension is repointed at the durable ledger (Principle 10): with the ledger
     * module installed the gate scores maintainer-health off the persisted answer for this repository, not a live
     * deps.dev probe on the admission path. The ledger IS a {@code HealthSource}, so this is the same overlay shape;
     * a coordinate with no stored record resolves to empty (no finding), the same safe default a live probe that
     * cannot resolve a coordinate produces. Absent the ledger module the gate keeps its live health source.
     */
    private static ComplianceGate overlaid(ComplianceGate gate, ArtifactStore store,
                                           List<ComplianceGate.Subject> inspected, String path) {
        ComplianceGate overlaid = gate;
        if (FINDINGS.isPresent()) {
            try {
                overlaid = overlaid.waivers(
                        WaiverLabels.overlayFor(FINDINGS.get().over(store), inspected, Clocks.now()));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not read waivers for " + path + "; suppressing nothing", failure);
            }
        }
        if (HEALTH.isPresent()) {
            overlaid = overlaid.health(HEALTH.get().over(store));
        }
        return overlaid;
    }

    // No withheld(path, store) override: this screen holds through the /quarantine<path> review pointer, and the publication
    // copies that hold onto the serving pointer (Publication.link writes the flag, unpublish lifts it, the
    // rebuild walk reconciles the two), so a serve reads it off the one pointer it reads anyway. An override probing
    // the review pointer here paid a second key on every download - measured 2026-09-12 as the first of a download's
    // four reads on every backing - and the router's miss path, the one place that must tell a hold from an absence
    // with no serving pointer to read, asks Publication.reviewPending beside the chain instead.

    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        ComplianceGate.Assessment assessment = assessed.get();
        List<ComplianceGate.Subject> inspected = subjects.get();
        String malformed = unparseable.get();
        boolean oversized = Boolean.TRUE.equals(oversizedFinding.get());
        String feedFailed = feedFailure.get();
        assessed.remove();
        subjects.remove();
        unparseable.remove();
        oversizedFinding.remove();
        feedFailure.remove();
        if (malformed != null) {
            // An inspector could not parse this upload: record the distinct inspection-failed finding on the
            // coordinate whatever the disposition (admitted-but-unscreened by default, or held), so the artifact
            // renders as "could not derive - not fully screened" rather than a silent clean.
            recordUnparseableFinding(store, artifact, inspected, malformed,
                    oversized ? "oversized" : "unparseable");
        }
        VerdictListener listener = VERDICTS.get();
        if (listener != null) {
            // Best-effort: count the verdict on EVERY publish path before the disposition's own writes, so a
            // downstream write failure never loses the decision from the metric.
            listener.recorded(artifact.ecosystem() == null ? "none" : artifact.ecosystem(), disposition.name());
        }
        switch (disposition) {
            case ACCEPT -> {
                StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
                // One recording per publish: the published facts, the local-upload origin (the content hash the store
                // computed on write - origin never re-reads the body), the declared licences and the provenance
                // summary land in one write of the version's document, and the identity index folds once with the
                // licences it ends up holding. Keyed by the coordinate a format describes the path to, else by the
                // inspected subject's: npm, PyPI, NuGet and RubyGems publish to a versionless envelope endpoint whose
                // path carries no version, and the inspector parsed the real per-version coordinate.
                recordPublish(inventory, artifact, inspected);
                // Persist the publish-time advisory answer for the just-accepted coordinate, keyed by the real
                // (feed, advisory-id) the scheduled sweep also writes - so a coordinate published after the last
                // sweep already carries its advisory findings instead of rendering clean until the next pass.
                recordAdvisoryFindings(store, artifact, inspected);
                // Persist the publish-time maintainer-health for the just-accepted coordinate, the health sibling of
                // the advisory persistence above - so a coordinate published after the last health sweep already
                // carries its health in the durable ledger the gate now reads, and admission of a later version of the
                // same coordinate scores off a populated ledger rather than the not-yet-swept fallback.
                recordHealth(store, artifact, inspected);
                // Continuity is learned from what actually landed: every signature that verified by a trusted signer
                // on an accepted publish is observed, so the next version by another signer is measured against it.
                recordMaintainers(store, inspected, artifact.path());
                reportSigners(store, inspected, artifact.path());
                if (assessment != null && assessment.verdict() == Verdict.QUARANTINE) {
                    // The gate held this upload and the chain still accepted it: the publication primitive joined the hold
                    // to the admission that already serves these very bytes - a rival's signature released the path
                    // while this upload's verdict was in flight - so nothing is held, and the log says so rather
                    // than losing the verdict: the gate's own reasons, under the outcome the path actually has.
                    List<String> reasons = new ArrayList<>();
                    reasons.add("Hold superseded: " + artifact.path() + " already serves these bytes, admitted with "
                            + "their signature before this upload's verdict landed, so the identical upload is "
                            + "accepted as that artifact and nothing is held; the gate had found:");
                    for (ComplianceGate.Finding finding : assessment.findings()) {
                        reasons.add(finding.detail());
                    }
                    new QuarantineLog(store).record(Clocks.now(), reviewPath(artifact, inspected),
                            coordinate(artifact, inspected), Verdict.ALLOW, reasons);
                }
                // A stale publish-time gate hold at this path is superseded: the gate just cleared a fresh upload
                // there, and serving the accepted artifact must not stay blocked by a verdict on a body that was
                // since replaced. But a retroactive KEV sweep writes its holds/kev record BEFORE it links the
                // /quarantine pointer, so blindly deleting the pointer would strand that record (a dangling
                // holds/kev entry until the next reanalysis interval) and drop a known-exploited hold a re-publish
                // must not clear. So only clear the pointer this screen's own kind of hold owns - when the sweep
                // owns it, leave both in place and let the reanalysis pass release it.
                if (store.readVersioned(Publication.quarantineKey(artifact.path())).isPresent()
                        && !sweepHeld(store, inventory, artifact)) {
                    store.delete(Publication.quarantineKey(artifact.path()));
                    // The serving pointer's copy of the superseded hold goes with it, before the accepted layout
                    // links the fresh bytes: a link carries its predecessor's flag, so a flag left here would hold
                    // the artifact this screen just cleared. A sweep-owned pointer left standing above keeps its
                    // copy too, which is what keeps a known-exploited hold effective across a re-publish.
                    new Publication(store).suppress(artifact.path(), false);
                    // The superseded hold's subject record goes with the pointer it explained: the record is
                    // reclaimed by the lifecycle that ends the hold, never by a sweep, and never because a module is
                    // absent. A sweep-owned pointer left standing above keeps its record too.
                    HeldSubjects.forget(store, artifact.path());
                }
            }
            case QUARANTINE, REJECT -> {
                // Nothing is observed for a held or refused upload, but a signer no source could place is still
                // reported wanted: that is how a discovery source learns what to fetch. A held upload's metadata is
                // what a reviewer sees and what discovery asks by, so its maintainers are recorded; a refused one's
                // is not, since nothing of it is kept.
                if (assessment != null && assessment.verdict() == Verdict.QUARANTINE) {
                    recordMaintainers(store, inspected, artifact.path());
                }
                reportSigners(store, inspected, artifact.path());
                List<String> reasons = new ArrayList<>();
                if (assessment != null) {
                    for (ComplianceGate.Finding finding : assessment.findings()) {
                        reasons.add(finding.detail());
                    }
                }
                if (malformed != null) {
                    // The hold's own reason when the artifact was held for being unparseable (no gate assessment): a
                    // scoped, operator-legible line naming the artifact and the inspector's parse-failure message, so a
                    // reviewer sees which artifact was held and why (§9 make-errors-visible), not a silent reject.
                    reasons.add("Could not fully screen the claimed artifact " + artifact.path()
                            + " - its quality inspector could not parse it: " + malformed);
                }
                if (feedFailed != null) {
                    // The hold's own reason when the artifact was held because an advisory feed failed closed mid-
                    // assessment (no completed verdict): the artifact and the feed-failure message, so a reviewer sees
                    // the hold is a screening outage to retry, not a policy rejection of the content itself.
                    reasons.add("Could not fully screen the artifact " + artifact.path()
                            + " - " + FEED_FAILED_CLOSED + ": " + feedFailed);
                }
                // The path a reviewer will meet this hold at, which is not always the path the screen was handed
                //. A format whose coordinate lives INSIDE the artifact commits under the only descriptor it
                // can build before the bytes are down - its push endpoint, one path every push of that format shares
                // - and re-keys the /quarantine review handle onto the package once the coordinate is readable. The
                // audit row and the held-subject record follow the handle, or the reviewer's reasons and their handle
                // name different paths for the same hold and HoldLifecycle's path-keyed reads answer about neither.
                // Both dispositions, not only the held one. An envelope-publish format (npm, PyPI, NuGet,
                // RubyGems) commits under one versionless push endpoint that every push of that format shares, so
                // a REJECT logged under the raw artifact.path() reads as the same path for every refusal in the
                // repository - and QuarantineLog.indexLatest keys the derived latest-verdict row by that path, so
                // those refusals overwrite one another's index row. Re-keying is safe for a refusal because it
                // derives a path or returns the screened one unchanged; a refusal has no review pointer to agree
                // with, so the only thing at stake is whether its row is distinguishable, and it was not.
                String reviewPath = reviewPath(artifact, inspected);
                new QuarantineLog(store).record(Clocks.now(), reviewPath, coordinate(artifact, inspected),
                        disposition == Disposition.QUARANTINE ? Verdict.QUARANTINE : Verdict.REJECT, reasons);
                if (disposition == Disposition.QUARANTINE) {
                    recordGateFindings(store, artifact, assessment);
                    // The screen-time half of the durable path -> coordinate record. The publication has just
                    // linked the /quarantine<path> review pointer for this disposition, and this is the moment the
                    // owning format is by construction installed - it is what screened the upload - so the subject is
                    // captured now and every later path-keyed question answers from it whether or not that module
                    // survives. It is the SAME resolution the accepted branch's sidecars use (the layout first, the
                    // inspected envelope subject for a versionless publish endpoint second), so a held upload and an
                    // accepted one agree on what the path was. A path that resolves to no coordinate is recorded as
                    // such: "asked, and there is no version here" is a fact a reader needs, and is not the same as no
                    // record at all. The write is inside the screen's own pre-commit window and its IOException
                    // propagates, so a hold whose subject could not be recorded fails the publish rather than standing
                    // unexplained.
                    Optional<StoreRepositoryInventory.Coordinate> subject =
                            publishedCoordinate(store, artifact, inspected);
                    HeldSubjects.record(store, reviewPath,
                            subject.map(StoreRepositoryInventory.Coordinate::ecosystem).orElse(artifact.ecosystem()),
                            subject.map(StoreRepositoryInventory.Coordinate::coordinate).orElse(null),
                            subject.map(StoreRepositoryInventory.Coordinate::version).orElse(null));
                    if (subject.isPresent() && assessment != null) {
                        recordHolds(store, subject.get(), assessment);
                    }
                }
            }
        }
    }

    /**
     * The publish-time hold-mapping round-trip check (CEP-P2 / C1-A2): once an accepted publish has been laid out in
     * its format's namespace, verify the format's REVERSE mapping resolves the very artifact just served. This is the
     * one after-commit hook that runs <em>after</em> the format links its served pointers (the interceptor
     * {@link #committed} runs before layout, when nothing the format serves exists yet), so it is where the pointers
     * are in hand and the check is two pointer reads. An interceptor's inherited observe hook is a no-op by default;
     * overriding it here opts this screen into riding the accepted publish for exactly this validation.
     */
    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // Contained on its own, ahead of the round-trip check: the two are unrelated concerns and a late-declaration
        // re-assessment must never decide whether the hold-mapping validation runs, nor the other way about.
        try {
            releaseCompleted(artifact, store);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not re-assess the artifacts " + artifact.path() + " completes; they stay held",
                    failure);
        }
        verifyHoldMapping(artifact, store);
    }

    /**
     * Release the neighbours this publish completed the declaration for - the late-declaration re-assessment
     * {@link QualityInspector#completes} exists for.
     *
     * <p>The case is Maven's and it is the ordinary one: {@code mvn deploy} sends the jar before the POM, so a jar
     * carrying no licence of its own is screened while the coordinate's licence document is still in flight, reads as
     * unknown, and is held by the {@code license-unknown} dial's own default. Nothing was wrong with the publish and
     * nothing is wrong with the artifact - the evidence simply had not arrived. When it does, each held neighbour is
     * re-assessed <em>through the ordinary publish gate</em> over its own stored bytes, and released only if that gate
     * now answers {@code ALLOW}.
     *
     * <p><b>This is not an override.</b> A neighbour still held for any other reason - a denied licence, an advisory,
     * a deny-listed coordinate, an unparseable body - fails the same assessment again and stays exactly where it is,
     * because the only thing that changed for it is a sibling it does not read. Nor does it re-decide settled
     * evidence: {@link GatePolicyProvider}'s idempotency clause asks that a re-screen reproduce the verdict the
     * artifact was published under, and this reproduces it faithfully - over a store that now holds one document more
     * than it did, which is the whole of what makes the answer legitimately different.
     *
     * <p>Suppressed while a review release is replaying ({@link #RELEASING}), so a released artifact whose format
     * re-publishes through the {@code Publication} cannot re-enter this and walk the same directory again.
     */
    private void releaseCompleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (RELEASING.get()) {
            return;
        }
        ComplianceGate current = gate.get();
        if (current == null) {
            return;
        }
        String published = artifact.path();
        Set<String> prefixes = new LinkedHashSet<>();
        for (QualityInspector inspector : INSPECTORS) {
            if (inspector.handles(published)) {
                inspector.completes(published).ifPresent(prefixes::add);
            }
        }
        for (String prefix : prefixes) {
            releaseCompletedUnder(current, store, published, prefix);
        }
    }

    /** Re-assess every held artifact directly under {@code prefix} except the one just published, releasing those the
     *  gate now clears. Each neighbour is contained on its own: one that will not inspect leaves the rest to be
     *  judged, since a body that cannot be parsed is a reason to keep THAT hold, not to abandon the pass. */
    private void releaseCompletedUnder(ComplianceGate gate, ArtifactStore store, String published, String prefix)
            throws IOException {
        Publication publication = new Publication(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        for (String child : store.list(Publication.quarantineKey(prefix))) {
            String path = prefix + child;
            if (path.equals(published)) {
                continue;
            }
            try {
                if (clearedByCompletion(gate, publication, inventory, store, path, published)) {
                    // The release is the ordinary one, so every hook, override and withhold marker is lifted exactly
                    // as a reviewer's release lifts them.
                    HoldLifecycle.release(store, path);
                    LOGGER.info("Released {}: its declaration was completed by the publish of {}, and the gate now "
                            + "clears it", path, published);
                }
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not re-assess held " + path + " after " + published + " published; it stays held",
                        failure);
            }
        }
    }

    /** Whether the gate now answers ALLOW for the artifact held at {@code path}, read back from its held blob with the
     *  siblings that have since landed in view. Anything short of a clean ALLOW - no held blob, no describable
     *  coordinate, nothing to gate, an unparseable body, a feed that failed closed - keeps the hold. */
    private boolean clearedByCompletion(ComplianceGate gate, Publication publication,
                                        StoreRepositoryInventory inventory, ArtifactStore store, String path,
                                        String published) throws IOException {
        Optional<String> held = publication.blob("/quarantine" + path);
        if (held.isEmpty()) {
            return false;
        }
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty()) {
            return false;
        }
        // The publish path's own read view, over the HELD blob rather than a freshly stored one: the sibling reads it
        // carries now resolve the document that has since been published, which is the entire point. The HELD view,
        // because a sidecar is withheld by its subject's hold - so an artifact held for the want of its signature
        // could never be released by that signature arriving, the re-assessment asking what a client would see and
        // being answered by the hold it is deciding about. Publication states the reasoning at heldContentOf.
        List<ComplianceGate.Subject> inspected = inspect(described.get(), publication.heldContentOf(held.get()));
        if (inspected.isEmpty()) {
            return false;
        }
        ComplianceGate.Assessment assessment = overlaid(gate, store, inspected, path).assess(inspected);
        // The version is being judged on the strength of what just landed - for Maven's order, the signature - so
        // this is where its signer is first seen: learned if it releases, wanted if nobody could place it.
        recordMaintainers(store, inspected, path);
        reportSigners(store, inspected, path, assessment.verdict() == Verdict.ALLOW);
        if (assessment.verdict() == Verdict.ALLOW) {
            return true;
        }
        // Still held, and possibly for a new reason: the sidecar that just landed may have said who signed the
        // artifact, and "no publisher signature" is no longer what a reviewer should read. The log's latest row for
        // the path is what the review queue shows, so it says what the re-assessment found.
        List<String> reasons = new ArrayList<>();
        reasons.add("Re-assessed after " + published + " landed; still held:");
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        new QuarantineLog(store).record(Clocks.now(), path, coordinate(described.get(), inspected),
                Verdict.QUARANTINE, reasons);
        return false;
    }

    /**
     * Verify that a blobs-namespace format resolves the publish it just laid out: the served request path is one its
     * {@code servedPaths} maps the coordinate version back to, and the stored content hash is one its
     * {@code blobHashes} resolves. A format whose {@code describe} emits a coordinate (so a retroactive KEV/license
     * sweep enumerates the version) but whose {@code blobKeys}/{@code servedPaths} resolve NOTHING back would hold
     * un-retractably - the RPM/conda/conan class that shipped five times and surfaced only in a later audit (a27/a6
     * Finding 1). Caught here it fails on the FIRST publish. On a break: emit
     * {@code jenreg.publish.holdmapping.broken{eco}} + one WARN, and {@code throw} only when
     * {@code jenreg.strict-hold-mapping} is on (every test config sets it) - production stays
     * alarm-not-abort, since a broken format must not DoS publishes (the {@code hold.unenforceable} gauge reasoning of
     * #204). Scoped strictly to the JUST-published path/hash, which the format just wrote, so an evicted-but-still-
     * enumerated sibling version - which legitimately resolves to nothing at describe time - never false-positives.
     * Only blobs-namespace ecosystems are checked: a {@code publish/}-namespace layout (Maven) or a non-blobs upload
     * has no such reverse mapping to verify, and a path that names no versioned artifact (an index, a packument, a
     * versionless envelope endpoint) is skipped exactly as the {@code published/} sidecar write is.
     */
    private static void verifyHoldMapping(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(artifact.path());
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return;   // the path names no versioned artifact (an index/packument/envelope) - nothing to round-trip
        }
        String ecosystem = described.get().ecosystem();
        if (ecosystem == null || !inventory.servesFromBlobs(ecosystem)) {
            return;   // a publish/-namespace (Maven) or non-blobs ecosystem has no blobs-namespace mapping to verify
        }
        String coordinate = described.get().coordinate();
        String version = described.get().version();
        // The two pointer reads: the served path just requested must be one the coordinate maps back to, and the
        // content hash just stored must be one the coordinate resolves. Both scoped to THIS publish's path/hash.
        boolean pathResolves = inventory.paths(ecosystem, coordinate, version).contains(artifact.path());
        boolean hashResolves = artifact.hash() != null
                && inventory.blobHashes(ecosystem, coordinate, version).contains(artifact.hash());
        if (pathResolves && hashResolves) {
            return;   // the reverse mapping resolves the publish just made - the format is wired
        }
        LOGGER.warn("Repository publish of {} {}:{} through the blobs-namespace {} format resolves no reverse hold "
                        + "mapping for the artifact just served ({}): servedPaths {} the request path, blobHashes {} "
                        + "the stored content hash. A retroactive known-exploited or license hold on this version "
                        + "could mark nothing and retract no served path - the blobKeys/servedPaths mapping is unwired. "
                        + "This is caught at publish rather than in a later audit.",
                ecosystem, coordinate, version, ecosystem, artifact.path(),
                pathResolves ? "contains" : "is MISSING", hashResolves ? "contains" : "is MISSING");
        HoldMappingBrokenListener meter = HOLD_MAPPING_BROKEN.get();
        if (meter != null) {
            meter.broken(ecosystem);
        }
        BooleanSupplier strict = STRICT_HOLD_MAPPING.get();
        if (strict != null && strict.getAsBoolean()) {
            throw new IOException("hold-mapping round-trip broken for " + ecosystem + " " + coordinate + ":" + version
                    + " at " + artifact.path() + " - the blobs-namespace format enumerates this version but its "
                    + "blobKeys/servedPaths resolve neither the served path nor the stored content hash "
                    + "(jenreg.strict-hold-mapping is on)");
        }
    }

    /**
     * Write the {@code holds/<kind>} record for every finding of the quarantining assessment that names a hold kind,
     * grouped by kind - the same record the kind's retroactive sweep writes before it links its hold pointers, so an
     * operator's release of this publish-time hold promotes the same subjects into the same sticky override and the
     * sweep never re-holds a version a human has cleared. Before the finding carried its kind, a gate hold wrote no
     * record and the KEV kind recovered the CVEs from the quarantine log's reason text by regular expression; a
     * finding whose wording did not match left the release without an override, and the next sweep re-held it.
     * Written inside the screen's pre-commit window like the held-subject record beside it, so a hold whose record
     * could not be written fails the publish rather than standing without it.
     */
    private static void recordHolds(ArtifactStore store, StoreRepositoryInventory.Coordinate subject,
                                    ComplianceGate.Assessment assessment) throws IOException {
        Map<String, Set<String>> byKind = new LinkedHashMap<>();
        for (ComplianceGate.Finding finding : assessment.findings()) {
            if (finding.hold() != null && !finding.hold().subjects().isEmpty()) {
                byKind.computeIfAbsent(finding.hold().kind(), _ -> new LinkedHashSet<>())
                        .addAll(finding.hold().subjects());
            }
        }
        for (Map.Entry<String, Set<String>> kind : byKind.entrySet()) {
            HoldKind.of(kind.getKey()).hold(store, subject.ecosystem(), subject.coordinate(), subject.version(),
                    kind.getValue());
        }
    }

    /**
     * Persist a quarantining assessment's reasons as structured rows in the findings ledger - one attributed
     * {@link Finding.Kind#GATE} row per gate finding, keyed by the coordinate the layout descriptor maps the path
     * to (the same coordinate the {@code published/} sidecar would use, so a later release's eviction reclaims the
     * rows) - beside the {@link QuarantineLog}'s flat audit line. Held to quarantines only: a rejected upload stores
     * no artifact whose lifecycle could ever reclaim its rows, so its trail stays the retention-pruned log. Best
     * effort like every derived write here - the hold and the log line are already durable, so a failed ledger write
     * must not fail the publish choreography - and a no-op when no findings module is installed.
     */
    private static void recordGateFindings(ArtifactStore store, ArtifactDescriptor artifact,
                                           ComplianceGate.Assessment assessment) {
        if (FINDINGS.isEmpty() || assessment == null || assessment.findings().isEmpty()) {
            return;
        }
        try {
            Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(artifact.path());
            if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
                return;
            }
            Findings ledger = FINDINGS.get().over(store);
            Instant now = Clocks.now();
            // One batched commit for the whole assessment's gate rows (SS4a), not one CAS per reason.
            List<Finding> rows = new ArrayList<>();
            for (ComplianceGate.Finding finding : assessment.findings()) {
                // The id is a stable digest of the reason text, so a re-screen of the same body refreshes the row.
                rows.add(Finding.of("gate-" + Integer.toHexString(finding.detail().hashCode()), GATE_SOURCE,
                                Finding.Kind.GATE, finding.verdict().name(), Severity.NONE, finding.detail(), now)
                        .withProvenance(artifact.path()));
            }
            ledger.recordAll(described.get().ecosystem(), described.get().coordinate(), described.get().version(),
                    rows);
        } catch (IOException | RuntimeException _) {
            // best-effort: the hold pointer and the quarantine log line carry the decision; the ledger catches up
            // on the next screen of the path
        }
    }

    /**
     * Persist the distinct inspection-failed finding for an artifact an inspector could not parse - one
     * {@link Finding.Kind#INSPECTION} row on the coordinate, so a reader sees "could not derive - not fully screened"
     * rather than a silent clean. Keyed by the layout descriptor's coordinate where the path maps to one, else the
     * path-derived subject's own coordinate (a versionless envelope publish, or a filename fallback), so the row is
     * always recorded rather than lost when the path does not describe. Best-effort like the sibling derived writes -
     * the WARNING log (and, when held, the quarantine log) already carry the failure - and a no-op when no findings
     * module is installed.
     */
    private static void recordUnparseableFinding(ArtifactStore store, ArtifactDescriptor artifact,
                                                 List<ComplianceGate.Subject> inspected, String reason,
                                                 String code) {
        if (FINDINGS.isEmpty()) {
            return;
        }
        try {
            String ecosystem;
            String coordinate;
            String version;
            Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(artifact.path());
            if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
                ecosystem = described.get().ecosystem();
                coordinate = described.get().coordinate();
                version = described.get().version();
            } else if (inspected != null && !inspected.isEmpty()) {
                ComplianceGate.Subject subject = inspected.getFirst();
                ecosystem = subject.ecosystem();
                coordinate = subject.coordinate();
                version = subject.version();
            } else {
                return;
            }
            if (coordinate == null || coordinate.isEmpty()) {
                return;
            }
            FINDINGS.get().over(store).record(ecosystem == null ? "" : ecosystem, coordinate,
                    version == null ? "" : version,
                    Finding.of("inspection-" + Integer.toHexString(artifact.path().hashCode()), INSPECTION_SOURCE,
                                    Finding.Kind.INSPECTION, code, Severity.NONE, reason, Clocks.now())
                            .withProvenance(artifact.path()));
        } catch (IOException | RuntimeException _) {
            // best-effort: the WARNING log and (when held) the quarantine log carry the failure; the ledger row is a
            // bonus that a later re-screen of the path refreshes
        }
    }

    /**
     * Persist the publish-time advisory answer for a just-accepted coordinate: re-query the deployment's named
     * advisory feeds - the same instances the gate assessed through, so the gate's own lookup just warmed each feed's
     * {@code FeedCache} and this is a cache read, not a fresh network pass - and record each hit as a structured
     * finding keyed by the real {@code (feed, advisory-id)} pair, exactly as the scheduled {@code VulnerabilityScanTask}
     * writes them ({@link AdvisoryFindings#of}). This closes the between-sweeps window: a coordinate published AFTER the
     * last sweep already carries its advisory findings rather than rendering clean until the next pass, and because the
     * rows key by {@code (source, id)} the later sweep converges on the identical rows instead of doubling them. Held
     * to ACCEPT, where the coordinate lands in {@code published/} and the sweep will revisit it - a quarantined or
     * rejected upload has no such sidecar, so persisting its advisory rows would strand them. Best-effort like every
     * derived write here, and fail-soft <em>per feed</em>: a feed the warm cache cannot answer (a feed failing
     * closed with nothing cached) is logged and metered, never a reason to fail an already-accepted publish. A no-op
     * when no findings module is installed or no feeds are wired.
     */
    private static void recordAdvisoryFindings(ArtifactStore store, ArtifactDescriptor artifact,
                                               List<ComplianceGate.Subject> inspected) {
        if (FINDINGS.isEmpty()) {
            return;
        }
        Supplier<SequencedMap<String, AdvisorySource>> supplier = FEEDS.get();
        if (supplier == null) {
            return;
        }
        SequencedMap<String, AdvisorySource> feeds = supplier.get();
        if (feeds == null || feeds.isEmpty()) {
            return;
        }
        Optional<StoreRepositoryInventory.Coordinate> published = publishedCoordinate(store, artifact, inspected);
        if (published.isEmpty()) {
            return;
        }
        StoreRepositoryInventory.Coordinate coordinate = published.get();
        Findings ledger = FINDINGS.get().over(store);
        Instant now = Clocks.now();
        // Accumulate every feed's rows, then commit them in ONE section mutate (SS4a); the per-feed query stays
        // fail-soft (a warm-cache miss defers to the sweep) but the persist is a single batched write per coordinate.
        List<Finding> rows = new ArrayList<>();
        for (Map.Entry<String, AdvisorySource> feed : feeds.entrySet()) {
            List<AdvisorySource.Advisory> found;
            try {
                found = feed.getValue().advisories(coordinate.ecosystem(), coordinate.coordinate(),
                        coordinate.version());
            } catch (RuntimeException failure) {
                // Fail-soft: a warm-cache miss whose refresh failed with nothing cached rethrows here (the feed fails
                // closed for the gate, but the publish is already accepted and stored). Log and meter the miss and move
                // on; the scheduled sweep records this feed's rows on its next pass.
                LOGGER.warn("Could not re-query advisory feed " + feed.getKey()
                        + " for " + coordinate.coordinate() + ":" + coordinate.version()
                        + " at publish; its rows wait for the next sweep", failure);
                FeedMissListener miss = FEED_MISSES.get();
                if (miss != null) {
                    miss.missed(feed.getKey());
                }
                continue;
            }
            for (AdvisorySource.Advisory advisory : found) {
                rows.add(AdvisoryFindings.of(advisory, feed.getKey(), artifact.path(), now));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            ledger.recordAll(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), rows);
        } catch (IOException | RuntimeException failure) {
            // best-effort: the artifact is stored and the sweep converges on these rows; a lost write only defers
            LOGGER.warn("Could not persist publish-time advisory findings for "
                    + coordinate.coordinate() + ":" + coordinate.version(), failure);
        }
    }

    /**
     * Persist the publish-time maintainer-health for a just-accepted coordinate: probe the deployment's live health
     * source - the same instance the scheduled sweep probes, so its {@code FeedCache} may already carry the answer - and
     * record it into the durable {@link HealthLedger} keyed by the coordinate (version-independent), exactly as the
     * {@code HealthScanTask} writes it. This closes the between-sweeps window: a coordinate published AFTER the last
     * health sweep already carries its health in the ledger the gate now reads, so admission of a LATER version of the
     * same coordinate scores off a populated ledger rather than the not-yet-swept fallback. Held to ACCEPT, where the
     * coordinate lands in {@code published/} and the sweep will revisit it. A coordinate the source scores nothing is
     * left unrecorded (unknown, not healthy). Best-effort like every derived write here - the artifact is already stored,
     * so a failed probe or write must not fail an accepted publish - and a no-op when no health-ledger module is
     * installed or no live source is wired; the sweep then records the coordinate on its next pass. The commit does NOT
     * stamp {@link build.jenesis.repository.health.HealthLedger#scanned health stamp}: a single-coordinate persistence is not a full scan, so
     * the staleness stamp stays the last full sweep's, never masking that a fresh publish outran the sweep.
     */
    /**
     * Tell the trust what the inspection found about signers: every signature that verified by a trusted signer on
     * a version the inspection could place on a coordinate is observed, so continuity is learned from what landed
     * (only when {@code accepted}), and every signature by a signer no source held a key for is reported wanted,
     * so a discovery source knows what to fetch. Best-effort by the seam's own contract: a lost observation delays
     * an expectation, a lost want delays a fetch, and neither can admit a signer.
     */
    static void reportSigners(ArtifactStore store, List<ComplianceGate.Subject> inspected, String path) {
        reportSigners(store, inspected, path, true);
    }

    /**
     * Record on the coordinate whom the artifact's metadata names as its maintainers ({@link Maintainers}) - for an
     * accepted or a held upload, never a refused one - before the signature question is asked, since Maven's
     * signature arrives one request after the POM and its re-assessment reads this record for whom to ask. It is
     * what a key found through a maintainer is judged against: such a key admits only a coordinate whose record
     * names that person. Best-effort like every derived write: a lost record delays a binding being satisfied and
     * can never admit a signer.
     */
    static void recordMaintainers(ArtifactStore store, List<ComplianceGate.Subject> inspected, String path) {
        Set<String> maintainers = maintainers(inspected);
        if (maintainers.isEmpty()) {
            return;
        }
        try {
            for (ComplianceGate.Subject subject : inspected) {
                if (!subject.contentScan() && subject.ecosystem() != null && subject.coordinate() != null) {
                    Maintainers.record(store, subject.ecosystem(), subject.coordinate(), maintainers);
                    return;
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not record the maintainers of {}; the next version records them again", path, failure);
        }
    }

    /** The identities every inspected subject names as maintainers - gathered across subjects, since the package
     *  subject carries the metadata and the signature subject the signatures. */
    private static Set<String> maintainers(List<ComplianceGate.Subject> inspected) {
        Set<String> maintainers = new LinkedHashSet<>();
        for (ComplianceGate.Subject subject : inspected == null ? List.<ComplianceGate.Subject>of() : inspected) {
            maintainers.addAll(Maintainer.ids(subject.maintainers()));
        }
        return maintainers;
    }

    static void reportSigners(ArtifactStore store, List<ComplianceGate.Subject> inspected, String path,
                              boolean accepted) {
        if (inspected == null || inspected.stream().noneMatch(subject -> !subject.signatures().isEmpty())) {
            return;
        }
        // Whom the artifact names travels with every want, so a discovery source that looks a key up by its owner
        // knows whom to ask and what the key it finds is bound to.
        Set<String> maintainers = maintainers(inspected);
        SignerTrust trust = SignerTrustProvider.trust(configuration(), store);
        for (ComplianceGate.Subject subject : inspected) {
            for (ComplianceGate.Signature signature : subject.signatures()) {
                if (signature.signer() == null) {
                    continue;
                }
                try {
                    if (signature.trusted() && accepted && subject.ecosystem() != null && subject.coordinate() != null
                            && subject.version() != null && !subject.version().isEmpty()) {
                        trust.observed(subject.ecosystem(), subject.coordinate(), subject.version(),
                                signature.signer(), Clocks.now());
                    } else if (signature.outcome() == ComplianceGate.Signature.Outcome.UNTRUSTED
                            && signature.keySource() == null) {
                        trust.wanted(signature.signer(), path, maintainers, Clocks.now());
                    }
                } catch (IOException | RuntimeException failure) {
                    LOGGER.warn("Could not report the signer of {}; the next version reports it again", path, failure);
                }
            }
        }
    }

    private static void recordHealth(ArtifactStore store, ArtifactDescriptor artifact,
                                     List<ComplianceGate.Subject> inspected) {
        if (HEALTH.isEmpty()) {
            return;
        }
        Supplier<HealthSource> supplier = HEALTH_SOURCE.get();
        if (supplier == null) {
            return;
        }
        HealthSource source = supplier.get();
        if (source == null || source == HealthSource.none()) {
            return;
        }
        Optional<StoreRepositoryInventory.Coordinate> published = publishedCoordinate(store, artifact, inspected);
        if (published.isEmpty()) {
            return;
        }
        StoreRepositoryInventory.Coordinate coordinate = published.get();
        Optional<HealthSource.Health> health;
        try {
            health = source.health(coordinate.ecosystem(), coordinate.coordinate());
        } catch (RuntimeException failure) {
            // The live source degrades to empty on its own (a ranking signal, not a hard gate); a probe that instead
            // throws is caught here so an accepted publish is never failed for it. The sweep records the coordinate next pass.
            LOGGER.warn("Could not probe maintainer-health for "
                    + coordinate.coordinate() + " at publish; its health waits for the next sweep", failure);
            return;
        }
        if (health.isEmpty()) {
            return;                                             // unscored: left unrecorded (unknown, not healthy)
        }
        try {
            HEALTH.get().over(store).record(coordinate.ecosystem(), coordinate.coordinate(), health.get(),
                    Clocks.now());
        } catch (IOException | RuntimeException failure) {
            // best-effort: the artifact is stored and the sweep converges on this record; a lost write only defers
            LOGGER.warn("Could not persist publish-time maintainer-health for "
                    + coordinate.coordinate(), failure);
        }
    }

    /** The {@code (ecosystem, coordinate, version)} that entered this repository's {@code published/} tree for the
     *  accepted upload - exactly what the scheduled sweep reads back and re-queries, so the publish-time advisory rows
     *  key identically and converge. It is the path descriptor's coordinate where the layout maps the path to one (a
     *  Maven publish), else the inspected subject's own coordinate for a versionless envelope publish
     *  (npm/PyPI/NuGet/RubyGems) - the same two sources {@link #recordPublish} keys its recording by. Empty when
     *  neither yields a coordinate (a checksum, generated metadata). */
    private static Optional<StoreRepositoryInventory.Coordinate> publishedCoordinate(
            ArtifactStore store, ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(artifact.path());
        if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
            return Optional.of(new StoreRepositoryInventory.Coordinate(
                    described.get().ecosystem(), described.get().coordinate(), described.get().version()));
        }
        return subjectCoordinate(inventory, inspected == null || inspected.isEmpty() ? null : inspected.getFirst());
    }

    /**
     * The coordinate an inspected subject names, spelled as the layout owning its ecosystem keys it. The subject reads
     * it out of the artifact as the artifact writes it - a NuGet id in its declared case - and the layout serves the
     * version under its own normal form, so a record keyed by the subject's spelling was a record no read resolved
     * to: a pushed package's signature, licences and hold records all landed in a document beside the one its reads
     * found. The subject's own spelling stands only where no installed layout places the ecosystem.
     */
    private static Optional<StoreRepositoryInventory.Coordinate> subjectCoordinate(
            StoreRepositoryInventory inventory, ComplianceGate.Subject subject) {
        if (subject == null || subject.coordinate() == null || subject.coordinate().isEmpty()
                || subject.version() == null || subject.version().isEmpty()) {
            return Optional.empty();
        }
        return inventory.canonical(subject.ecosystem(), subject.coordinate(), subject.version())
                .or(() -> Optional.of(new StoreRepositoryInventory.Coordinate(
                        subject.ecosystem(), subject.coordinate(), subject.version())));
    }

    /** Whether ANY retroactive enforcement sweep owns a hold on this path's coordinate - a {@code holds/} record of
     *  any discovered kind (KEV, license, reachability, a plugged future one), written before its {@code /quarantine}
     *  pointer. When one does, an accepted publish must not clear the pointer: doing so would strand the sweep's hold
     *  record and let an unprivileged re-upload launder a human-review hold (before this only the KEV kind was
     *  consulted, so a re-publish declaring clean metadata cleared a license-retro hold). The check reads the durable
     *  {@link HoldRecords} and then the discovered {@link HoldReleaseObserver}s, so a new hold kind joins by writing a
     *  record, never an edit here - and, since the earlier work, a kind whose module has been UNINSTALLED still counts, so
     *  uninstalling a compliance module cannot turn an ordinary re-upload into a laundering channel for the holds it
     *  had placed. A path a format DID place and that no sweep holds is not sweep-owned, so a plain publish-time gate
     *  hold is cleared as before.
     *
     *  <p><b>The one remaining way absence answered here is closed at this caller.</b> {@code anyHolds} keys
     *  on the coordinate a request path resolves to, and that resolution needs the owning FORMAT installed - the one
     *  dependence left standing and left open, for want of a durable path &rarr; coordinate record. With
     *  that format's module gone the path resolves to nothing, every record read has no key to look under, and the
     *  answer degrades to "not held" - so this leg would delete {@code publish/quarantine<path>}, the review queue's
     *  only index of the hold, on an accepted upload to that path. The question here is not "is anything held" but
     *  "may this screen clear a pointer it may not own", and unresolvable is not ownership: an unplaceable path
     *  answers sweep-owned and the pointer stays. The cost is a stale publish-time pointer left standing while the
     *  format is uninstalled - visible, and lifted by a review release - against a hold silently laundered. */
    private static boolean sweepHeld(ArtifactStore store, StoreRepositoryInventory inventory,
                                     ArtifactDescriptor artifact) throws IOException {
        if (inventory.describe(artifact.path()).isEmpty()) {
            return true;    // no installed format places this path, so no hold on it can be proved absent
        }
        return HoldReleaseObserver.anyHolds(store, artifact.path());
    }

    /** On an accepted publish an inspector claimed, record the artifact's own declared licenses as a sidecar keyed
     *  exactly like the publish-time sidecar (the layout descriptor's coordinate, not the inspector subject's, so the
     *  {@code licenses/} key joins the {@code published/} one the search sweep enumerates). The first subject is the
     *  artifact itself - the root of the inspected set, ahead of any transitive dependency - so its licenses are the
     *  artifact's own; an empty list is still recorded, marking the artifact as inspected-but-license-free so the
     *  sweep indexes it as unknown rather than re-parsing it. A claimed path that maps to no coordinate (a checksum,
     *  generated metadata) is skipped, as the publish-time sidecar is. */
    /**
     * One {@link Recording} per accepted publish. The path's coordinate where an installed format describes it, else
     * the inspected subject's; the origin is the accepted body's hash; the licences and the provenance summary are
     * the first inspected subject's, when an inspector ran. A publish that resolves to no coordinate at all - a path
     * nothing describes and no subject - records nothing, as before.
     */
    private static void recordPublish(StoreRepositoryInventory inventory, ArtifactDescriptor artifact,
                                      List<ComplianceGate.Subject> inspected) throws IOException {
        ComplianceGate.Subject subject = inspected == null || inspected.isEmpty() ? null : inspected.getFirst();
        Instant now = Clocks.now();
        Recording recording = inventory.recording(artifact.path(), now).orElse(null);
        if (recording == null) {
            Optional<StoreRepositoryInventory.Coordinate> named = subjectCoordinate(inventory, subject);
            if (named.isEmpty()) {
                return;
            }
            recording = inventory.recording(named.get().ecosystem(), named.get().coordinate(), named.get().version(),
                    false, now);
        }
        recording.origin(artifact.hash());
        if (subject != null) {
            List<LicenseInventory.Declared> declared = new ArrayList<>();
            for (ComplianceGate.DeclaredLicense license : subject.licenses()) {
                declared.add(new LicenseInventory.Declared(license.name(), license.url()));
            }
            recording.licenses(declared);
            if (subject.dependencies() != null) {
                // Recorded only where the inspector read the manifest for them: an empty list is a manifest that
                // declares none, and a subject no inspector read for them leaves the section as it was.
                recording.dependencies(subject.dependencies().stream()
                        .map(dependency -> new DependencySection.Declared(dependency.coordinate(),
                                dependency.requirement()))
                        .toList());
            }
            ComplianceGate.Attestation attestation = subject.attestation();
            if (attestation != null) {
                recording.provenance(attestation.artifactPresent() && attestation.artifactDigest() != null,
                        attestation.artifactDigest());
            }
        }
        // Signatures are read from EVERY subject, not from the first. A signature subject carries no licensable
        // identity, so InspectionMerge sorts it last - reading only the head would find the format's package subject
        // and silently record no signature for every artifact whose format also has an inspector, which is all of
        // them that matter.
        List<ComplianceGate.Signature> signatures = inspected == null ? List.of() : inspected.stream()
                .flatMap(each -> each.signatures().stream())
                .toList();
        Optional<ComplianceGate.Signature> summary = ComplianceGate.Signature.summarising(signatures);
        if (summary.isPresent()) {
            recording.signature(summary.get().outcome().name(),
                    summary.get().signer() == null ? null : summary.get().signer().wire(),
                    summary.get().quality() == null ? null : summary.get().quality().grade().name(),
                    summary.get().location(), summary.get().keySource(), summary.get().details());
        }
        recording.commit();
    }

    /** The coordinate the log names: what the inspector parsed, or the descriptor's own, or the bare path when the
     *  withholding verdict came from another screen over an artifact this one had nothing to say about. */
    private static String coordinate(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        if (inspected != null && !inspected.isEmpty()) {
            ComplianceGate.Subject subject = inspected.getFirst();
            return subject.coordinate() + ":" + subject.version();
        }
        return artifact.coordinate() == null ? artifact.path() : artifact.coordinate() + ":" + artifact.version();
    }

    /** Read a claimed artifact back from the store for its inspectors - the one place the screen materialises
     *  content, and only for an upload an inspector claims; the unclaimed bulk of a large artifact streams past. The
     *  read is capped at {@link #inspectionLimit()}, so even a claimed but pathologically large jar is gated from a
     *  bounded prefix rather than pulled whole into heap. Every inspector that claims the path runs over that one
     *  bounded read (a format inspector and the content scanner compose - {@link InspectionMerge} keeps the package
     *  subject first), not just the first to match. */
    private static List<ComplianceGate.Subject> inspect(ArtifactDescriptor artifact, Content content)
            throws IOException {
        String path = artifact.path();
        List<QualityInspector> claiming = new ArrayList<>();
        for (QualityInspector inspector : INSPECTORS) {
            if (inspector.handles(path)) {
                claiming.add(inspector);
            }
        }
        // An inspector that only reads what sits beside an artifact handles a raw path without claiming it unless a
        // sidecar is actually there: nothing claimed, nothing read - the unclaimed bulk of a raw upload streams past.
        QualityInspector.Lookup siblings = siblings(content);
        if (claiming.stream().noneMatch(inspector -> inspector.claims(path, siblings))) {
            return List.of();
        }
        byte[] metadata;
        boolean truncated;
        try (InputStream in = content.open()) {
            metadata = in.readNBytes(inspectionLimit());
            // Whether the artifact ran past the inspection window: one byte beyond the prefix means the inspectors saw
            // only a truncated head, so an empty result may be "metadata beyond the window" rather than "nothing here".
            truncated = in.read() != -1;
        }
        if (truncated) {
            // The artifact is larger than the most any inspector is handed in one array, and what to do about
            // that is the operator's call rather than this screen's. Only STREAM reaches a verdict about what
            // the artifact CONTAINS; the other two refuse on its size and say so, because nothing read it.
            QualityInspector.Oversized policy = QualityInspector.oversized(configuration());
            if (policy != QualityInspector.Oversized.STREAM) {
                throw new OversizedArtifactException(policy, artifact.size(), inspectionLimit());
            }
            return streamed(artifact, content, metadata, claiming, siblings);
        }
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        for (QualityInspector claimed : claiming) {
            QualityInspector inspector = bound(claimed, content);
            subjects.addAll(contained(inspector, path, () -> inspector.inspect(path, metadata, siblings)));
        }
        if (truncated && InspectionMerge.noPackageSubject(subjects)) {
            // A claimed artifact whose declaration sat BEYOND the 32 MiB inspection prefix yields no subjects from the
            // truncated head. That must NOT read as "nothing to gate" and silently ACCEPT - a padded archive with
            // trailing metadata would then publish un-screened, with nothing logged. Fall back to a filename-derived
            // coordinate subject so the deny-list and license dimensions still bite, and the incomplete screening is
            // recorded (quarantined/rejected with a reason) rather than passed silently.
            //
            // The guard asks whether any PACKAGE subject came back, not whether the list is empty, and the two differ
            // exactly when a second inspector claims the same path. A content-scan subject - a detected secret, an
            // inbound attestation, a publisher's signature - satisfies "not empty" while carrying no licensable
            // identity for the license and deny-list dimensions to bite on, so reading emptiness here let any content
            // inspector that found something in a truncated head silently switch this fallback off. The proxy leg had
            // the same defect and is fixed the same way; stating it in one predicate is what stops the two drifting.
            //
            // The fallback is APPENDED rather than substituted, because substituting would drop the very content
            // finding that made the list non-empty in order to add a coordinate.
            List<ComplianceGate.Subject> withFallback = new ArrayList<>(subjects);
            withFallback.add(pathDerivedSubject(artifact));
            return InspectionMerge.order(withFallback);
        }
        return InspectionMerge.order(subjects);
    }

    /**
     * Screen a claimed artifact bigger than the prefix by reading it from the store as a STREAM - the default answer
     * to {@link QualityInspector#OVERSIZED_KEY}, and the one that reaches a verdict about the artifact rather than
     * about its size.
     *
     * <p>This is the same leg the hardened proxy screens an upstream artifact through
     * ({@link QualityInspector#inspectArtifact(String, QualityInspector.Content, QualityInspector.Lookup)}), handed a
     * re-openable body over the blob just stored. An inspector that overrides it streams the whole artifact under the
     * full-body tier; one that does not is bridged down to the same front prefix it would have seen anyway and says
     * so in its own answer, so nothing here silently upgrades what an inspector actually read.
     *
     * <p><b>What it buys, measured.</b> The format inspectors crack their archives through
     * {@code BoundedArchive.zipEntry}, which already takes an {@code InputStream} - so a declaration stored at the
     * BACK of a large archive becomes reachable without a new parser and without random access. Before this,
     * a 1.5 GiB NuGet package and a {@code .deb} of the same size were both held on the shipped defaults, because the
     * signature material each carries inside itself sits past the bound and an unreadable signature is scored with
     * the untrusted dial.
     *
     * <p>The truncation fallback below is the byte[] leg's, on the honest predicate: an inspector that ran out of
     * body or out of budget reports an incomplete inspection, and a claimed artifact that yielded no package subject
     * still falls back to a path-derived one rather than reading as nothing to gate.
     */
    private static List<ComplianceGate.Subject> streamed(ArtifactDescriptor artifact, Content content,
                                                         byte[] metadata, List<QualityInspector> claiming,
                                                         QualityInspector.Lookup siblings) throws IOException {
        String path = artifact.path();
        QualityInspector.Content body = new QualityInspector.Content() {
            @Override
            public long size() {
                // Counted as the bytes streamed into the store, so this never stats the blob it just wrote.
                return artifact.size();
            }

            @Override
            public InputStream open() throws IOException {
                return content.open();
            }
        };
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        boolean incomplete = false;
        for (QualityInspector claimed : claiming) {
            QualityInspector inspector = bound(claimed, content);
            if (!inspector.streams()) {
                // It would read the same front prefix through either leg, so it is handed the array the read
                // above already produced, by the very call a publish has always made. Its answer covers a
                // prefix of a larger body, which is what the fallback below is for.
                subjects.addAll(contained(inspector, path, () -> inspector.inspect(path, metadata, siblings)));
                incomplete = true;
                continue;
            }
            QualityInspector.Inspection inspection =
                    contained(inspector, path, () -> inspector.inspectArtifact(path, body, siblings));
            subjects.addAll(inspection.subjects());
            incomplete |= !inspection.complete();
        }
        if (incomplete && InspectionMerge.noPackageSubject(subjects)) {
            List<ComplianceGate.Subject> withFallback = new ArrayList<>(subjects);
            withFallback.add(pathDerivedSubject(artifact));
            return InspectionMerge.order(withFallback);
        }
        return InspectionMerge.order(subjects);
    }

    /** Trust is rebound per request, not per process: it is tenant state, and the screen is what knows which tenant
     *  this publish belongs to. An inspector that verifies nothing never implements the seam and is handed through
     *  untouched. Both inspection legs bind the same way, so they bind in one place. */
    private static QualityInspector bound(QualityInspector claimed, Content content) {
        return claimed instanceof TrustAware aware && TRUST_INSTALLED
                ? aware.withTrust(SignerTrustProvider.trust(configuration(), content.store()))
                : claimed;
    }

    /** What an inspection call may do that is not returning an answer, named. */
    @FunctionalInterface
    private interface Inspecting<T> {

        T inspect() throws IOException;
    }

    /**
     * Run one inspector inside the screen's containment - the host half of the inspector fan-out, in one place
     * because both legs need it and a second copy is how the two come to fail differently.
     *
     * <p>The identity is read from the inspector's CLASS BEFORE the call and never asked of the guest afterwards: a
     * handler that re-enters a broken guest to ask what to blame is how containment gets defeated from inside its own
     * handler. Every failure shape the SPI permits therefore leaves this named, and leaves it on the SAME fail-closed
     * leg: {@code QualityInspector.inspect} declares {@code throws IOException}, so a plain IOException (a
     * ZipException off a truncated central directory, an EOFException off a half-written body) is as legal an
     * inspector failure as a RuntimeException - and it was the one shape that escaped the screen's containment,
     * because the caller catches MalformedArtifactException and RuntimeException and nothing in between. That shape
     * reached the publisher as a raw 500 with no hold, no recorded finding and no diagnostic, while the same
     * inspector raising an IllegalStateException over the same bytes was held with a legible reason.
     *
     * <p>The could-not-parse leg is kept exactly as it was - it is a distinct, contract-bearing answer the caller
     * routes on - and only gains the name of the inspector that raised it.
     */
    private static <T> T contained(QualityInspector inspector, String path, Inspecting<T> call) throws IOException {
        String identity = inspector.getClass().getName();
        try {
            return call.inspect();
        } catch (MalformedArtifactException malformed) {
            throw new MalformedArtifactException(identity + " could not parse " + path + ": "
                    + reason(malformed), malformed);
        } catch (IOException | RuntimeException failure) {
            // Both re-raised as the inspection-fault the caller already fails closed on, attributed to the
            // inspector. A deployment carries seventeen of these; "a quality inspector threw" names none of them.
            throw new InspectionFault(identity + " threw inspecting " + path + ": " + reason(failure), failure);
        }
    }

    /**
     * A discovered {@link QualityInspector} failed inspecting an upload, named. Unchecked so it lands on the screen's
     * existing inspection-fault leg (fail closed, hold the upload, record the reason) rather than needing a second
     * one, and carrying the inspector's implementation class in its message because a deployment installs seventeen
     * of them and "a quality inspector threw" names none. It exists to carry an attribution out of {@link #inspect},
     * never as an API - which is why it is package-private and why nothing catches it by type.
     */
    static final class InspectionFault extends IllegalStateException {

        InspectionFault(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The one-line reason an attributed message carries: the failure's own message where it has one, else its type.
     *  The same shape the {@code Contributions.reason} uses on the collected-report side. */
    private static String reason(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * The already-published-sibling lookup this screen hands its inspectors on the publish leg: a thin adapter over
     * the publication seam's <em>own</em> two sibling reads, one compliance leg delegating to one store leg.
     *
     * <p><b>Each leg delegates to its counterpart; neither is derived from the other.</b> That is the whole content of
     * the earlier fix. Before it, this screen handed inspectors {@code content::sibling} - a method reference
     * that supplied only the whole-document read and let {@link QualityInspector.Lookup}'s since-deleted default
     * synthesise the bounded one from it. The synthesis inherited the whole-document ceiling, so
     * {@code AttestationInspector}, which asks for a 32 MiB bounded read of the artifact its referrer names, got an
     * exception above {@link PublishInterceptor.Content#LARGEST_SIBLING} (8 MiB) here while the proxy leg - which
     * overrode the default and streams - answered {@code truncated} for the very same sibling. An 8-32 MiB companion
     * therefore degraded on one leg and raised on the other. The publication seam has offered a real bounded read since
     * 0.10.0 ({@link PublishInterceptor.Content#sibling(String, int)}, capped at the store), so the screen now hands
     * that through unchanged and the two legs agree.
     *
     * <p>The two {@code Bounded} records are the same pair of values in two SPIs (the store contract and the
     * compliance contract, which must not depend on each other's shapes), so the adapter re-wraps rather
     * than re-reads - the array is handed on, never copied, since duplicating it would double the very heap the bound
     * protects.
     *
     * <p>Exposed for the bounded-read guard test, which drives both legs directly rather than through a registered
     * inspector - the same seam {@code ProxyScreen.siblingLookup()} offers on the other leg.
     */
    public static QualityInspector.Lookup siblings(Content content) {
        return new QualityInspector.Lookup() {

            @Override
            public Optional<byte[]> fetch(String path) throws IOException {
                // The whole-document read, delegated: it carries the publication seam's LARGEST_SIBLING ceiling and throws
                // past it, which is what the compliance Lookup's own fetch clause promises.
                Optional<byte[]> published = content.sibling(path);
                return published.isPresent() ? published : owned(path, content.store());
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchBounded(String path, int limit) throws IOException {

                // The bounded-fact read, delegated to the publication seam's own bounded leg - capped at the store, honouring
                // the CALLER's limit rather than LARGEST_SIBLING, and reporting the overflow instead of raising on it.
                Optional<QualityInspector.Lookup.Bounded> published = content.sibling(path, limit)
                        .map(bounded -> new QualityInspector.Lookup.Bounded(bounded.content(), bounded.truncated()));
                if (published.isPresent()) {
                    return published;
                }
                return ownedPrefix(path, content.store(), limit).map(read -> new QualityInspector.Lookup.Bounded(
                        read.length > limit ? Arrays.copyOf(read, limit) : read, read.length > limit));
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchStored(String path, int limit) throws IOException {
                // The stored pointer, not the serving resolution: one point read for a companion that is absent,
                // where the serving question pays the withhold-chain probe and the content-addressed marker on top.
                // Measured as three store reads per Maven publish for an .asc that is usually not there, which is a
                // fixed cost on the request path for asking a question this seam does not need answered.
                Optional<String> stored = new Publication(content.store()).blob(path);
                if (stored.isEmpty()) {
                    // Falls back to the blobs-namespace formats' own serving keys, which have no publish/ pointer to
                    // read: those resolve through the format and are unaffected by the distinction being drawn here.
                    return ownedPrefix(path, content.store(), limit).map(read -> new QualityInspector.Lookup.Bounded(
                            read.length > limit ? Arrays.copyOf(read, limit) : read, read.length > limit));
                }
                try (InputStream in = new Blobs(content.store()).open(stored.get())) {
                    byte[] read = in.readNBytes(limit + 1);
                    return Optional.of(read.length > limit
                            ? new QualityInspector.Lookup.Bounded(Arrays.copyOf(read, limit), true)
                            : new QualityInspector.Lookup.Bounded(read, false));
                }
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchRecorded(String key, int limit) throws IOException {
                // A format's own record, by the key it wrote it under: one versioned point read, bounded after the
                // fact, since a record is a few lines and an index copy is held to the signature bound by its writer.
                return content.store().readVersioned(key).map(recorded -> recorded.content().length > limit
                        ? new QualityInspector.Lookup.Bounded(Arrays.copyOf(recorded.content(), limit), true)
                        : new QualityInspector.Lookup.Bounded(recorded.content(), false));
            }
        };
    }

    /**
     * The sibling as the FORMAT that owns the layout serves it, for the formats whose artifacts are not published
     * through the generic {@code publish/<path>} pointer - Hugging Face, npm, PyPI, Go, Conan and the
     * rest of the blobs-namespace group.
     *
     * <p>The generic read above answers for every format that uses that pointer, and empty for every format that
     * does not, however plainly the artifact is being served. That silence is what kept an inspector from reading
     * the document beside the one it was handed: a Hugging Face model card is a sibling of the file it describes and
     * was invisible from it, so a repository could not be gated on the licence it declares. A format that keeps its
     * own key space now answers the same question through {@link BlobLayout#servingKey}, which resolves the way its
     * serving does - the same revision rules, the same absence.
     *
     * <p>Read whole under the publication seam's own sibling ceiling, so this path is bounded exactly like the one it backs
     * up rather than becoming the way a large companion gets pulled into heap.
     */
    private static Optional<byte[]> owned(String path, ArtifactStore store) throws IOException {
        return ownedPrefix(path, store, PublishInterceptor.Content.largestSibling());
    }

    private static Optional<byte[]> ownedPrefix(String path, ArtifactStore store, int limit) throws IOException {
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (!(format instanceof BlobLayout layout)) {
                continue;
            }
            Optional<String> key = layout.servingKey(path, store);
            if (key.isEmpty()) {
                continue;
            }
            Blobs blobs = new Blobs(store);
            // A hold applies to a sibling exactly as it applies to a served read: an inspector must not be handed the
            // bytes of a document the registry is withholding, or a screen would reach a verdict off evidence no
            // client can see. Blobs.read resolves the pointer to its blob, which is also why this cannot be a plain
            // store.open of the key - that would hand back the pointer's own body, a hash.
            if (blobs.withheld(key.get())) {
                return Optional.empty();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!blobs.read(key.get(), buffer)) {
                return Optional.empty();
            }
            byte[] read = buffer.toByteArray();
            return Optional.of(read.length > limit + 1 ? Arrays.copyOf(read, limit + 1) : read);
        }
        return Optional.empty();
    }

    /**
     * As {@link #screenFromPath(ArtifactDescriptor, ComplianceGate)}, with the content findings an inspector made
     * beside content that derived no package subject: the path-derived subject goes through the unclaimed
     * assessment, the content-scan subjects through the full one (whose licence dimension skips them), and the
     * stronger verdict decides, every finding recorded.
     */
    private Disposition screenFromPath(ArtifactDescriptor artifact, ComplianceGate current,
                                       List<ComplianceGate.Subject> content) {
        ComplianceGate.Subject subject = pathDerivedSubject(artifact);
        ComplianceGate.Assessment unclaimed = current.assessUnclaimed(subject);
        ComplianceGate.Assessment scanned = current.assess(content);
        List<ComplianceGate.Finding> findings = new ArrayList<>(unclaimed.findings());
        findings.addAll(scanned.findings());
        ComplianceGate.Assessment assessment =
                new ComplianceGate.Assessment(ComplianceGate.strongest(findings), List.copyOf(findings));
        assessed.set(assessment);
        List<ComplianceGate.Subject> all = new ArrayList<>();
        all.add(subject);
        all.addAll(content);
        subjects.set(List.copyOf(all));
        Disposition disposition = switch (assessment.verdict()) {
            case ALLOW -> Disposition.ACCEPT;
            case QUARANTINE -> Disposition.QUARANTINE;
            case REJECT -> Disposition.REJECT;
        };
        LOGGER.info(disposition == Disposition.ACCEPT
                ? "Admitting content screened from its path with what was found beside it (deny-list clear): "
                        + artifact.path()
                : "Screened content from its path and what was found beside it to " + disposition + ": "
                        + artifact.path());
        return disposition;
    }

    /** Screen content whose inspection produced no package subject - raw or un-inspected content nothing claimed,
     *  and equally content an inspector claimed and derived nothing from - against the operator deny-list and the
     *  coordinate/feed dimensions, from a path-derived subject and WITHOUT reading the body.
     *  {@link ComplianceGate#assessUnclaimed} deliberately skips the license/discovered dimensions, so an ordinary
     *  raw upload is NOT over-quarantined as unknown-license while a deny-listed or advised coordinate is still
     *  held/rejected however it was delivered. The assessment is stashed for {@link #committed} exactly as a parsed
     *  one, so a non-ACCEPT outcome records its reasons in the quarantine log; an admitted (or withheld) upload is
     *  also logged for the observability the audit asks for. */
    private Disposition screenFromPath(ArtifactDescriptor artifact, ComplianceGate current) {
        ComplianceGate.Subject subject = pathDerivedSubject(artifact);
        ComplianceGate.Assessment assessment = current.assessUnclaimed(subject);
        assessed.set(assessment);
        subjects.set(List.of(subject));
        Disposition disposition = switch (assessment.verdict()) {
            case ALLOW -> Disposition.ACCEPT;
            case QUARANTINE -> Disposition.QUARANTINE;
            case REJECT -> Disposition.REJECT;
        };
        LOGGER.info(disposition == Disposition.ACCEPT
                ? "Admitting content screened from its path (nothing derived a package subject, deny-list clear): "
                        + artifact.path()
                : "Screened content from its path to " + disposition + ": " + artifact.path());
        return disposition;
    }

    /** Route an artifact an inspector claimed but could not parse: FAIL CLOSED. A could-not-parse outcome means the
     *  gate's input silently failed to derive - the license and vulnerability dimensions never saw the artifact's real
     *  coordinate - so it must never read as a clean admit (§9: no silent fallback on a correctness-bearing path). The
     *  upload is HELD in quarantine unconditionally, never admitted: "could not fully screen ⇒ do not serve". The hold
     *  is made visible rather than a silent reject - a WARNING naming the path is logged, the unparseable meter is
     *  bumped ({@code jenreg.gate.unparseable}, tagged by format), and the parse-failure reason is stashed so {@link
     *  #committed} names it in the quarantine log (which artifact, which inspector's message, why) and records a
     *  distinct {@link Finding.Kind#INSPECTION} finding on the coordinate - so an operator sees a scoped reason for the
     *  hold and can investigate or release it. A real artifact that trips a stricter parser is held by design (the owner
     *  accepted this publish-rejection risk); the quarantine review/release flow is the operator's override. */
    /** Fail closed when a quality inspector threw a non-{@link MalformedArtifactException} runtime error inspecting the
     *  upload (an unhandled edge over hostile content). The inspector failing to inspect is a could-not-fully-screen
     *  exactly like an unparseable body, so this holds the artifact in quarantine and records the inspection-failed
     *  reason through the same marker {@link #screenMalformed} uses - never letting the raw error escape to the publisher
     *  and never admitting the unscreened bytes. */
    private Disposition screenInspectorFailure(ArtifactDescriptor artifact, RuntimeException failure) {
        String format = artifact.ecosystem() == null ? "none" : artifact.ecosystem();
        // An InspectionFault already names the inspector, the path and the reason - the attribution the fan-out
        // captured before it called the guest. Anything else reaching here came from outside that loop and
        // is reported as it stands.
        String message = failure instanceof InspectionFault ? failure.getMessage() : reason(failure);
        LOGGER.warn("A quality inspector threw inspecting " + artifact.path() + " (" + message + "); holding it in "
                + "quarantine (fail-closed) and recording an inspection-failed finding - could not fully screen, so "
                + "it is not served", failure);
        UnparseableListener meter = UNPARSEABLE_METER.get();
        if (meter != null) {
            meter.detected(format);
        }
        unparseable.set("inspector threw: " + message);
        subjects.set(List.of(pathDerivedSubject(artifact)));
        assessed.remove();
        return Disposition.QUARANTINE;
    }

    /**
     * An artifact past the inspection prefix on a deployment that chose not to stream it.
     *
     * <p>Unchecked so it rides out of {@link #inspect} the way {@link InspectionFault} does, and carrying the policy
     * because the two values it can hold lead to different dispositions. It is the one refusal in this screen that is
     * not a failure: nothing was attempted, so nothing failed, and the message says exactly that.
     */
    static final class OversizedArtifactException extends IllegalStateException {

        private final QualityInspector.Oversized policy;

        OversizedArtifactException(QualityInspector.Oversized policy, long size, int bound) {
            super("the artifact is " + size + " bytes, past the " + bound + "-byte inspection prefix ("
                    + QualityInspector.PREFIX_INSPECTION_LIMIT_KEY + "), and this deployment's "
                    + QualityInspector.OVERSIZED_KEY + " is " + policy + " - so it was not screened, and nothing "
                    + "here is a statement about what it contains");
            this.policy = policy;
        }

        QualityInspector.Oversized policy() {
            return policy;
        }
    }

    /**
     * Hold or refuse an artifact too large to screen, on the operator's own instruction.
     *
     * <p>It records the same inspection finding the fail-closed legs record, under its own code so the two are
     * distinguishable in the ledger: "could not screen this, and here is why" is the same fact whether the cause was
     * a corrupt archive or a size this deployment declines to read, and an operator reviewing the hold needs the
     * reason either way. No gate assessment is stashed, because none ran.
     */
    private Disposition screenOversized(ArtifactDescriptor artifact, OversizedArtifactException oversized) {
        LOGGER.warn("Not screening " + artifact.path() + ": " + oversized.getMessage());
        unparseable.set(oversized.getMessage());
        oversizedFinding.set(Boolean.TRUE);
        subjects.set(List.of(pathDerivedSubject(artifact)));
        assessed.remove();
        return oversized.policy() == QualityInspector.Oversized.QUARANTINE
                ? Disposition.QUARANTINE
                : Disposition.REJECT;
    }

    private Disposition screenMalformed(ArtifactDescriptor artifact, MalformedArtifactException failure) {
        String format = artifact.ecosystem() == null ? "none" : artifact.ecosystem();
        LOGGER.warn(
                "Could not parse claimed artifact " + artifact.path() + "; holding it in quarantine (fail-closed) and "
                        + "recording an inspection-failed finding - could not fully screen, so it is not served", failure);
        UnparseableListener meter = UNPARSEABLE_METER.get();
        if (meter != null) {
            meter.detected(format);
        }
        unparseable.set(failure.getMessage() == null ? "unparseable artifact" : failure.getMessage());
        // A path-derived subject so the quarantine log still names the coordinate the hold is on; no gate assessment is
        // stashed (none ran, and none could - the input would not parse), so committed() names the malformed reason
        // from the stashed marker rather than from a verdict's findings.
        subjects.set(List.of(pathDerivedSubject(artifact)));
        assessed.remove();
        return Disposition.QUARANTINE;
    }

    /** Fail closed when an advisory feed threw mid-assessment (a rate limit, a mirror outage): the gate assesses through
     *  the feed, so its {@code assess} raised rather than returning a verdict. Could-not-fully-screen holds the upload in
     *  quarantine with a legible, scoped reason (the same discipline {@link #screenMalformed} applies to an unparseable
     *  body), never admitting the unscreened bytes as a silent clean and never letting the raw error escape to the
     *  publisher. The inspected subjects (where the body parsed) name the coordinate the hold is on, so the quarantine
     *  log still identifies the held artifact; a path-derived subject stands in when nothing parsed. */
    private Disposition screenFeedFailure(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected,
            RuntimeException failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        LOGGER.warn("Could not fully screen " + artifact.path() + "; an advisory feed failed closed - holding it in "
                + "quarantine (fail-closed) rather than serving unscreened bytes or surfacing a 500", failure);
        feedFailure.set(message);
        subjects.set(inspected.isEmpty() ? List.of(pathDerivedSubject(artifact)) : inspected);
        assessed.remove();
        return Disposition.QUARANTINE;
    }

    /**
     * Where a hold this screen is recording will actually be reviewable: the screened path, unless the
     * descriptor names no coordinate and an installed blobs-namespace layout derives exactly one served path for the
     * coordinate an inspector <em>did</em> read out of the body - the shape of a format whose coordinate lives inside
     * the artifact, which commits under its push endpoint and re-keys its {@code /quarantine} handle onto the package
     * afterwards.
     *
     * <p>Three conditions, each load-bearing. A descriptor that already names the coordinate is the artifact's own
     * path and nothing re-keys it. The derivation is the store-free
     * {@link StoreRepositoryInventory#plannedPaths} one, because this runs BEFORE the layout and the pointer a
     * store-backed enumeration probes for does not exist yet. And exactly one path: a format serving a version at
     * several aliases has no single review handle to agree with, so the screened path stays what it was rather than
     * this picking one and disagreeing with the other.
     */
    private static String reviewPath(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        // A null inspected list is the leg that never ran assess on this thread - a hook-contract driver calling
        // committed directly, or a disposition another interceptor reached. There is nothing to derive from, so the
        // screened path stands, exactly as the coordinate() and publishedCoordinate() reads beside this one do.
        if (inspected == null || (artifact.coordinate() != null && artifact.version() != null)) {
            return artifact.path();
        }
        for (ComplianceGate.Subject subject : inspected) {
            List<String> planned = StoreRepositoryInventory.plannedPaths(
                    subject.ecosystem(), subject.coordinate(), subject.version());
            if (planned.size() == 1) {
                return planned.getFirst();
            }
        }
        return artifact.path();
    }

    /** The stand-in subject for an artifact screened from its path alone rather than from parsed content: an inspector
     *  claimed it but could not read it within the inspection window (a truncated archive), OR no inspector claimed it
     *  at all (raw / un-inspected content). Its own coordinate where the layout descriptor carries one, else its
     *  filename, so the deny-list still applies. It declares no license: the truncated-claimed path runs the full
     *  {@link ComplianceGate#assess} so the unknown-license dimension holds it, while the unclaimed path runs {@link
     *  ComplianceGate#assessUnclaimed}, which skips that dimension - a raw upload is screened for the deny-list without
     *  being over-quarantined as unknown-license. */
    private static ComplianceGate.Subject pathDerivedSubject(ArtifactDescriptor artifact) {
        String ecosystem = artifact.ecosystem() == null ? "" : artifact.ecosystem();
        String coordinate = artifact.coordinate() != null ? artifact.coordinate() : fileName(artifact.path());
        String version = artifact.version() == null ? "" : artifact.version();
        return new ComplianceGate.Subject(ecosystem, coordinate, version, List.of());
    }

    /** The last path segment - the filename a truncated-artifact fallback subject is coordinated by when the layout
     *  maps it to no coordinate. */
    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
