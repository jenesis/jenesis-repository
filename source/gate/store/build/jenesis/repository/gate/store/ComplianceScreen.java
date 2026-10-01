package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.StoreBindings;
import build.jenesis.repository.gate.InspectionMerge;
import build.jenesis.repository.gate.QuarantineLog;

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
 * <p>The screen is discovered by {@code ServiceLoader} and held once per process, so it is constructed without a
 * deployment. A deployment hands it one {@link Binding} - its gate, its per-tenant gates, its advisory feeds and
 * health source, its meters and the hold-mapping dial - by {@linkplain Binding#bind binding its store}, and every
 * call the screen receives carries a store derived from that one: {@link #assess} through {@code content.store()},
 * {@link #committed} and {@link #onPublished} as their argument, and {@link #rescreen} as its first. So a publish is
 * judged by the deployment whose store it runs over, and two deployments in one process never judge each other's
 * uploads.
 *
 * <p><b>A call that finds no binding is inert only while no deployment is bound in the process.</b> Inert means every
 * upload is accepted, which is right for a process that bound no gate at all and wrong for one that did: there an
 * unbound store is a path that lost the deployment's binding - a store built beside the deployment's root, a
 * decorator that does not forward it - and accepting through it would disable the gate without a word. So while any
 * {@link Binding} is open, an unbound {@link #assess}, {@link #committed} or {@link #rescreen} throws, which refuses
 * the publish. The withhold read side is unaffected either way, since it is store truth, not policy. An embedder or
 * a test that wants one screen with one gate, whatever store it is handed, uses an explicit constructor instead.
 *
 * <p>This class is the verdict and its binding: it owns the binding a deployment hands it, the per-publish
 * state carried from {@link #assess} to {@link #committed}, and the decision of what runs when. The work it
 * orchestrates is in three package peers it hands that state to explicitly - {@link PublishInspection} reads the
 * artifact into subjects, {@link PublishRecorder} writes down what a routed outcome established, and
 * {@link PublishHolds} records, retires, releases and verifies holds.
 */
public final class ComplianceScreen implements PublishInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplianceScreen.class);

    /**
     * The bindings open in this process. It carries no deployment's values - a publish reads those off its own store
     * - only whether any deployment is bound, which is what turns a call that lost its binding into a refusal rather
     * than an unscreened admission. Process-wide by necessity: the question is about the process, and a store that
     * carries no binding has nothing else to ask.
     */
    private static final Set<Binding> OPEN = ConcurrentHashMap.newKeySet();

    /** How an upload is read into subjects, built from the inspectors and the signer trust this JVM discovered -
     *  what the module path holds, which is one answer for every deployment in the process. */
    private static final PublishInspection INSPECTION =
            new PublishInspection(QualityInspector.all(), !SignerTrustProvider.installed().isEmpty());

    /** The findings ledger, when a persistence module is installed - the structured sibling of the quarantine
     *  log's flat reason line; empty when none is. Process-wide like {@link #INSPECTION}: it is the module path's
     *  answer, and the ledger it opens is always over the store a call carries. */
    private static final Optional<FindingsProvider> FINDINGS = FindingsProvider.installed();

    /** The durable maintainer-health ledger, when a persistence module is installed. Present, it is overlaid onto the
     *  gate before assess (so the health dimension scores off the persisted answer, not a live probe) and
     *  written at commit for a just-accepted coordinate; absent, the gate keeps its live health source and the screen
     *  persists no health. */
    private static final Optional<HealthLedgerProvider> HEALTH = HealthLedgerProvider.installed();

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
     *  not policy. Thread-scoped and cleared in a {@code finally} by the replay, so it can never leak past it - and
     *  carrying no deployment's values, it cannot hand one deployment's state to another. */
    private static final ThreadLocal<Boolean> RELEASING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The binding an explicit constructor gave this screen, which it uses whatever store it is handed; null for the
     *  discovered screen, which finds its binding on the store. */
    private final Binding explicit;

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

    /** The {@code ServiceLoader} constructor: the screen judges each call by the {@link Binding} its store carries. */
    public ComplianceScreen() {
        this.explicit = null;
    }

    /** An explicitly configured screen - one gate, whatever store it is handed, and no meters, feeds or health
     *  source: the seam a test or an embedder uses instead of a deployment's binding. */
    public ComplianceScreen(Supplier<ComplianceGate> gate) {
        this(binding().gate(gate).build());
    }

    /** An explicitly configured screen judging every call by {@code binding}, whatever store it is handed. */
    public ComplianceScreen(Binding binding) {
        this.explicit = Objects.requireNonNull(binding, "binding");
    }

    /** A new binding, to be {@linkplain Binding.Builder#open opened} by a deployment or
     *  {@linkplain Binding.Builder#build built} for an explicit screen. */
    public static Binding.Builder binding() {
        return new Binding.Builder();
    }

    /** What {@link #rescreen} came to. */
    public enum Rescreened {

        /** Nothing is held at the path: never held, or released or discarded already. */
        NOT_HELD,

        /** The gate cleared the held artifact and it was released, exactly as a reviewer's release releases it. */
        RELEASED,

        /** The gate still holds it; the review queue's latest row for the path says why. */
        HELD,

        /** No gate is bound for the tenant, so nothing could be decided and the hold stands. */
        UNSCREENED
    }

    /**
     * Re-assess the artifact {@code tenant}'s repository holds at {@code path}, because evidence it was waiting on
     * has landed off the publish path - a content scan's report, recorded where a dimension
     * {@linkplain ComplianceGate#bound bound} to the stored artifact reads it - and release it if the gate now clears
     * it. The gate is the one a publish to that tenant assesses through, its per-repository overlays and the stored
     * artifact's binding included, so the answer is the one a publish of the same bytes would get today. This is not
     * an override: an artifact still held for any other reason fails the same assessment and stays held, its log row
     * headed by {@code because}.
     *
     * @param store the repository's own scoped store, which carries the deployment's {@link Binding}
     * @throws IllegalStateException when {@code store} carries no binding while a deployment is bound in this process
     */
    public static Rescreened rescreen(ArtifactStore store, String tenant, String path, String because)
            throws IOException {
        Binding binding = bound(store);
        ComplianceGate current = binding == null ? null : binding.tenantGates.apply(tenant);
        if (current == null) {
            return Rescreened.UNSCREENED;
        }
        return PublishHolds.rescreen(INSPECTION,
                (inspected, held) -> overlaid(current, store, inspected, held).assess(inspected),
                binding.recorder(), store, path, because);
    }

    /** A registry-free callback a deployment binds so a committed verdict is counted: {@code (format, verdict)}
     *  where verdict is the {@link Disposition} name. */
    @FunctionalInterface
    public interface VerdictListener {
        void recorded(String format, String verdict);
    }

    /** A registry-free callback a deployment binds so a commit-time feed re-query that failed closed (the warm cache
     *  had nothing and the refresh could not reach the feed) is counted: {@code (feed)} names the feed that missed. */
    @FunctionalInterface
    public interface FeedMissListener {
        void missed(String feed);
    }

    /** A registry-free callback a deployment binds so an artifact an inspector could not parse is counted:
     *  {@code (format)} names the ecosystem/format of the unparseable upload. */
    @FunctionalInterface
    public interface UnparseableListener {
        void detected(String format);
    }

    /** A registry-free callback a deployment binds so a broken publish-time hold mapping is counted: {@code (eco)}
     *  names the ecosystem whose blobs-namespace format resolved no served path or content hash for the artifact it
     *  just laid out. */
    @FunctionalInterface
    public interface HoldMappingBrokenListener {
        void broken(String ecosystem);
    }

    /**
     * Everything a deployment hands the screen, as one value its store carries ({@link #bind}). The listeners are
     * registry-free on purpose: the Micrometer counters live in the distribution that binds them, so this module
     * stays free of any metrics dependency.
     *
     * <p>Opened by a deployment ({@link Builder#open}) and closed with it: while open, a call through a store carrying
     * no binding is refused rather than admitted unscreened. Built without opening ({@link Builder#build}) for an
     * explicit screen, which is not a deployment and so arms nothing.
     */
    public static final class Binding implements AutoCloseable, build.jenesis.repository.store.PublishPathWiring {

        /** The gate for the publishing thread - resolved off the publishing request's tenant - or null for none. */
        private final Supplier<ComplianceGate> gate;

        /** The gate for a named tenant, for a re-assessment made off any request ({@link #rescreen}). */
        private final Function<String, ComplianceGate> tenantGates;

        /** Counts every committed verdict, across every publish path (the deploy, staging, batch). */
        private final VerdictListener verdicts;

        /** The named advisory feeds - the same instances the gate assesses through - re-queried per feed at commit,
         *  so a just-accepted coordinate's advisory findings are persisted at once rather than rendering clean until
         *  the next sweep. The gate's assess just warmed each feed's cache, so the re-query is a cache read. */
        private final Supplier<SequencedMap<String, AdvisorySource>> feeds;

        /** Counts a commit-time feed re-query the warm cache could not answer, rather than dropping it silently; the
         *  publish is never failed for it. */
        private final FeedMissListener feedMisses;

        /** Counts an artifact an inspector could not parse ({@code jenrepo.gate.unparseable}, by format). */
        private final UnparseableListener unparseable;

        /** Whether a blobs-namespace format that resolves no reverse hold mapping for the publish it just laid out
         *  throws (failing the publish) or only alarms - {@code jenrepo.strict-hold-mapping}, off in production so one
         *  broken format cannot stop publishes, on in every test that publishes through a format. */
        private final BooleanSupplier strictHoldMapping;

        /** Counts a publish whose blobs-namespace reverse mapping does not resolve the artifact just served. */
        private final HoldMappingBrokenListener holdMappingBroken;

        /** The live maintainer-health source - the same instance the health sweep probes through - consulted per
         *  accepted coordinate at commit, so a just-published coordinate carries its health in the ledger at once. */
        private final Supplier<HealthSource> healthSource;

        private Binding(Builder builder) {
            this.gate = builder.gate;
            this.tenantGates = builder.tenantGates;
            this.verdicts = builder.verdicts;
            this.feeds = builder.feeds;
            this.feedMisses = builder.feedMisses;
            this.unparseable = builder.unparseable;
            this.strictHoldMapping = builder.strictHoldMapping;
            this.holdMappingBroken = builder.holdMappingBroken;
            this.healthSource = builder.healthSource;
        }

        /** {@code store} carrying this binding, as do all its scopes: what a deployment publishes through. */
        public ArtifactStore bind(ArtifactStore store) {
            return bindings().over(store);
        }

        /** This binding as the store bindings that carry it. */
        public StoreBindings bindings() {
            return StoreBindings.of(Binding.class, this);
        }

        /** What a commit records through: the discovered ledgers, and this binding's feeds, feed-miss sink and
         *  live health source. */
        private PublishRecorder recorder() {
            return new PublishRecorder(FINDINGS, HEALTH, feeds, feedMisses, healthSource);
        }

        /** Retire this binding: a call through a store carrying no binding is inert again once none is open. */
        @Override
        public void close() {
            OPEN.remove(this);
        }

        /** Assembles a {@link Binding}; everything unset is absent - no gate, no meters, no feeds, the lenient hold
         *  mapping. */
        public static final class Builder {

            private Supplier<ComplianceGate> gate = () -> null;
            private Function<String, ComplianceGate> tenantGates = _ -> null;
            private VerdictListener verdicts;
            private Supplier<SequencedMap<String, AdvisorySource>> feeds;
            private FeedMissListener feedMisses;
            private UnparseableListener unparseable;
            private BooleanSupplier strictHoldMapping = () -> false;
            private HoldMappingBrokenListener holdMappingBroken;
            private Supplier<HealthSource> healthSource;

            private Builder() {
            }

            /** The gate a publish assesses through, resolved on the publishing thread; a null answer screens nothing.
             *  */
            public Builder gate(Supplier<ComplianceGate> gate) {
                this.gate = Objects.requireNonNull(gate, "gate");
                return this;
            }

            /** The gate per tenant, for the re-assessments {@link #rescreen} makes off the request path. */
            public Builder tenantGates(Function<String, ComplianceGate> tenantGates) {
                this.tenantGates = Objects.requireNonNull(tenantGates, "tenantGates");
                return this;
            }

            /** The sink every committed verdict is counted through. */
            public Builder verdicts(VerdictListener verdicts) {
                this.verdicts = verdicts;
                return this;
            }

            /** The named advisory feeds a committed publish re-queries to persist its advisory findings at once. */
            public Builder advisoryFeeds(Supplier<SequencedMap<String, AdvisorySource>> feeds) {
                this.feeds = feeds;
                return this;
            }

            /** The sink a commit-time feed re-query that failed closed is counted through. */
            public Builder advisoryFeedMisses(FeedMissListener feedMisses) {
                this.feedMisses = feedMisses;
                return this;
            }

            /** The sink an artifact an inspector could not parse is counted through. */
            public Builder unparseableArtifacts(UnparseableListener unparseable) {
                this.unparseable = unparseable;
                return this;
            }

            /** Whether a broken publish-time hold mapping fails the publish ({@code true}) or only alarms. */
            public Builder strictHoldMapping(BooleanSupplier strictHoldMapping) {
                this.strictHoldMapping = Objects.requireNonNull(strictHoldMapping, "strictHoldMapping");
                return this;
            }

            /** The sink a broken publish-time hold mapping is counted through. */
            public Builder holdMappingBroken(HoldMappingBrokenListener holdMappingBroken) {
                this.holdMappingBroken = holdMappingBroken;
                return this;
            }

            /** The live maintainer-health source a committed publish persists the coordinate's health from. */
            public Builder healthSource(Supplier<HealthSource> healthSource) {
                this.healthSource = healthSource;
                return this;
            }

            /** The binding, for an explicit screen: not a deployment, so it arms nothing in the process. */
            public Binding build() {
                return new Binding(this);
            }

            /** The binding a deployment hands its store, open until closed: while it is, a call through a store
             *  carrying no binding is refused rather than admitted unscreened. */
            public Binding open() {
                Binding binding = new Binding(this);
                OPEN.add(binding);
                return binding;
            }
        }
    }

    /** What an unbound commit records through: the discovered ledgers alone, with no feeds, sink or health source. */
    private static final Binding NO_BINDING = binding().build();

    /**
     * The binding {@code store} carries; null when it carries none and no deployment is bound in this process, the
     * inert screen of a process that bound no gate.
     *
     * @throws IllegalStateException when {@code store} carries none while a deployment is bound: the call reached the
     *         screen through a path that lost the deployment's binding, and answering it would admit unscreened
     */
    private static Binding bound(ArtifactStore store) {
        Optional<Binding> carried = store.bindings().get(Binding.class);
        if (carried.isPresent()) {
            return carried.get();
        }
        if (!OPEN.isEmpty()) {
            throw new IllegalStateException("The compliance screen was handed a store that carries no deployment "
                    + "binding (" + store.identity() + ") while a deployment is bound in this process; refusing "
                    + "rather than admitting unscreened. Every store a deployment publishes through must be derived "
                    + "from the store it bound, and every decorator must forward its delegate's bindings.");
        }
        return null;
    }

    /** The binding this call runs under: the explicit one, else the one its store carries, as {@link #bound}. */
    private Binding binding(ArtifactStore store) {
        return explicit != null ? explicit : bound(store);
    }

    /** As {@link #binding}, for an observer leg, which is contained and so must not be the place a lost binding is
     *  reported: an unbound store answers no binding there, and the publish's own {@link #assess} refused it. */
    private Binding observing(ArtifactStore store) {
        return explicit != null ? explicit : store.bindings().get(Binding.class).orElse(null);
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
        Binding binding = binding(content.store());
        ComplianceGate current = binding == null ? null : binding.gate.get();
        if (current == null) {
            return Disposition.ACCEPT;
        }
        List<ComplianceGate.Subject> inspected;
        try {
            inspected = INSPECTION.inspect(artifact, content);
        } catch (PublishInspection.OversizedArtifactException oversized) {
            // The artifact is past the inspection prefix and this deployment has said not to stream it.
            // Unlike the two legs below this is not a failure of anything - nothing was attempted - so
            // the reason recorded is about the artifact's SIZE and claims nothing about its contents.
            return screenOversized(artifact, oversized);
        } catch (MalformedArtifactException malformed) {
            // An inspector claimed this artifact but could not parse it (truncated, corrupt, a decompression bomb).
            // That is distinct from "parsed fine, declares nothing": it must never read as a silent clean. The gate's
            // input silently failed to derive, so the license/vulnerability dimensions never saw the real coordinate -
            // fail closed and HOLD it (could not fully screen ⇒ do not serve), recording a scoped, legible reason.
            return screenMalformed(artifact, malformed, binding.unparseable);
        } catch (RuntimeException failure) {
            // An inspector threw a NON-Malformed runtime error parsing this upload - an unhandled edge over hostile
            // content (an NPE, an index/arithmetic fault) that was not wrapped as a MalformedArtifactException. The
            // gate never saw a derived coordinate, exactly as for the malformed case, so it fails closed and HOLDS it
            // (could not fully screen ⇒ do not serve) rather than letting the raw error escape to the publisher as a
            // 500 or admitting the unscreened bytes - the same discipline screenMalformed and screenFeedFailure apply.
            return screenInspectorFailure(artifact, failure, binding.unparseable);
        }
        if (inspected.isEmpty()) {
            // Nothing derived a package subject, so no parsed coordinate ever reached the gate - screen the path's
            // own coordinate against the operator deny-list and the feeds rather than admitting the bytes unscreened.
            //
            // Whether an inspector CLAIMED the path is deliberately no part of this. A claim is a declaration of
            // interest, not an assessment: an inspector that claims a path and then derives nothing has screened
            // exactly as much as one that never looked. The signature inspector claims an Ivy jar, because the layout
            // declares a sidecar convention, and derives no subject when the publisher signed nothing - so taking
            // "claimed and found nothing" for "already screened" would admit a jar carrying a CRITICAL advisory
            // against its own coordinate.
            return screenFromPath(artifact, current);
        }
        if (InspectionMerge.noPackageSubject(inspected)) {
            // Content findings and no package subject - a Sigstore bundle beside a file nothing parsed as a package.
            // The file is still screened from its path against the deny-list, and what was found beside it is
            // assessed with it: a bundle beside a deny-listed name neither switches the deny-list off nor goes
            // unexamined.
            return screenFromPath(artifact, overlaid(current, content.store(), inspected, artifact), inspected);
        }
        current = overlaid(current, content.store(), inspected, artifact);
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
     * applied. Extracted rather than inlined because a publish is not the only caller - the late-declaration
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
     * <p><b>Health.</b> The health dimension is repointed at the durable ledger: with the ledger
     * module installed the gate scores maintainer-health off the persisted answer for this repository, not a live
     * deps.dev probe on the admission path. The ledger IS a {@code HealthSource}, so this is the same overlay shape;
     * a coordinate with no stored record resolves to empty (no finding), the same safe default a live probe that
     * cannot resolve a coordinate produces. Absent the ledger module the gate keeps its live health source.
     *
     * <p><b>The stored artifact.</b> Every dimension is {@linkplain ComplianceGate#bound bound} to this repository and
     * to the artifact as it was stored, so a dimension that answers from what the repository recorded about these
     * bytes - a content scan's report - reads it for this assessment.
     */
    private static ComplianceGate overlaid(ComplianceGate gate, ArtifactStore store,
                                           List<ComplianceGate.Subject> inspected, ArtifactDescriptor artifact) {
        String path = artifact.path();
        ComplianceGate overlaid = gate.bound(store, artifact);
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

    // No withheld(path, store) override: this screen holds through the /quarantine<path> review pointer, and the
    // publication copies that hold onto the serving pointer (Publication.link writes the flag, unpublish lifts it, the
    // rebuild walk reconciles the two), so a serve reads it off the one pointer it reads anyway. An override probing
    // the review pointer here would pay a second key on every download - one of a download's four reads on every
    // backing - and the router's miss path, the one place that must tell a hold from an absence
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
        Binding binding = binding(store);
        PublishRecorder recorder = (binding == null ? NO_BINDING : binding).recorder();
        if (malformed != null) {
            // An inspector could not parse this upload: record the distinct inspection-failed finding on the
            // coordinate whatever the disposition (admitted-but-unscreened by default, or held), so the artifact
            // renders as "could not derive - not fully screened" rather than a silent clean.
            recorder.recordUnparseableFinding(store, artifact, inspected, malformed,
                    oversized ? "oversized" : "unparseable");
        }
        VerdictListener listener = binding == null ? null : binding.verdicts;
        if (listener != null) {
            // Best-effort: count the verdict on EVERY publish path before the disposition's own writes, so a
            // downstream write failure never loses the decision from the metric.
            listener.recorded(artifact.ecosystem() == null ? "none" : artifact.ecosystem(), disposition.name());
        }
        switch (disposition) {
            case ACCEPT -> {
                StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
                // One recording per publish, then the derived facts it closes the between-sweeps window for.
                recorder.accepted(store, inventory, artifact, inspected);
                if (assessment != null && assessment.verdict() == Verdict.QUARANTINE) {
                    PublishHolds.logSuperseded(store, artifact, inspected, assessment);
                }
                PublishHolds.retireStale(store, inventory, artifact);
            }
            case QUARANTINE, REJECT -> {
                // Nothing is observed for a held or refused upload, but a signer no source could place is still
                // reported wanted: that is how a discovery source learns what to fetch. A held upload's metadata is
                // what a reviewer sees and what discovery asks by, so its maintainers are recorded; a refused one's
                // is not, since nothing of it is kept.
                if (assessment != null && assessment.verdict() == Verdict.QUARANTINE) {
                    recorder.recordMaintainers(store, inspected, artifact.path());
                }
                recorder.reportSigners(store, inspected, artifact.path(), false);
                List<String> reasons = new ArrayList<>();
                if (assessment != null) {
                    for (ComplianceGate.Finding finding : assessment.findings()) {
                        reasons.add(finding.detail());
                    }
                }
                if (malformed != null) {
                    // The hold's own reason when the artifact was held for being unparseable (no gate assessment): a
                    // scoped, operator-legible line naming the artifact and the inspector's parse-failure message, so a
                    // reviewer sees which artifact was held and why (errors made visible), not a silent reject.
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
                // The path a reviewer will meet this hold at, which is not always the path the screen was handed. A
                // format whose coordinate lives INSIDE the artifact commits under the only descriptor it
                // can build before the bytes are down - its push endpoint, one path every push of that format shares
                // - and re-keys the /quarantine review handle onto the package once the coordinate is readable. The
                // audit row and the held-subject record follow the handle, or the reviewer's reasons and their handle
                // name different paths for the same hold and HoldLifecycle's path-keyed reads answer about neither.
                // Both dispositions, not only the held one. An envelope-publish format (npm, PyPI, NuGet,
                // RubyGems) commits under one versionless push endpoint that every push of that format shares, so
                // a REJECT logged under the raw artifact.path() reads as the same path for every refusal in the
                // repository - and QuarantineLog.indexLatest keys the derived latest-verdict row by that path, so
                // those refusals would overwrite one another's index row. Re-keying is safe for a refusal because it
                // derives a path or returns the screened one unchanged; a refusal has no review pointer to agree
                // with, so what is at stake is only that its row stays distinguishable.
                String reviewPath = PublishHolds.reviewPath(artifact, inspected);
                new QuarantineLog(store).record(Clocks.now(), reviewPath, PublishHolds.coordinate(artifact, inspected),
                        disposition == Disposition.QUARANTINE ? Verdict.QUARANTINE : Verdict.REJECT, reasons);
                if (disposition == Disposition.QUARANTINE) {
                    recorder.recordGateFindings(store, artifact, assessment);
                    PublishHolds.recordHeld(store, reviewPath, artifact, inspected, assessment);
                }
            }
        }
    }

    /**
     * The publish-time hold-mapping round-trip check: once an accepted publish has been laid out in
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
        Binding binding = observing(store);
        try {
            releaseCompleted(artifact, store, binding);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not re-assess the artifacts " + artifact.path() + " completes; they stay held",
                    failure);
        }
        PublishHolds.verifyHoldMapping(artifact, store, binding == null ? null : binding.holdMappingBroken,
                binding == null ? null : binding.strictHoldMapping);
    }

    /** Release the neighbours this publish completed the declaration for ({@link PublishHolds#releaseCompleted}),
     *  re-assessed through the gate this publish assessed through, overlays included. Suppressed while a review
     *  release is replaying ({@link #RELEASING}), so a released artifact whose format re-publishes through the
     *  {@code Publication} cannot re-enter this and walk the same directory again. */
    private void releaseCompleted(ArtifactDescriptor artifact, ArtifactStore store, Binding binding)
            throws IOException {
        if (RELEASING.get() || binding == null) {
            return;
        }
        ComplianceGate current = binding.gate.get();
        if (current == null) {
            return;
        }
        PublishHolds.releaseCompleted(INSPECTION,
                (inspected, held) -> overlaid(current, store, inspected, held).assess(inspected),
                binding.recorder(), store, artifact.path());
    }

    /**
     * As {@link #screenFromPath(ArtifactDescriptor, ComplianceGate)}, with the content findings an inspector made
     * beside content that derived no package subject: the path-derived subject goes through the unclaimed
     * assessment, the content-scan subjects through the full one (whose licence dimension skips them), and the
     * stronger verdict decides, every finding recorded.
     */
    private Disposition screenFromPath(ArtifactDescriptor artifact, ComplianceGate current,
                                       List<ComplianceGate.Subject> content) {
        ComplianceGate.Subject subject = PublishInspection.pathDerivedSubject(artifact);
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
        ComplianceGate.Subject subject = PublishInspection.pathDerivedSubject(artifact);
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

    /** Fail closed when a quality inspector threw a non-{@link MalformedArtifactException} runtime error inspecting the
     *  upload (an unhandled edge over hostile content). The inspector failing to inspect is a could-not-fully-screen
     *  exactly like an unparseable body, so this holds the artifact in quarantine and records the inspection-failed
     *  reason through the same marker {@link #screenMalformed} uses - never letting the raw error escape to the
     *  publisher and never admitting the unscreened bytes. */
    private Disposition screenInspectorFailure(ArtifactDescriptor artifact, RuntimeException failure,
                                               UnparseableListener meter) {
        String format = artifact.ecosystem() == null ? "none" : artifact.ecosystem();
        // An InspectionFault already names the inspector, the path and the reason - the attribution the fan-out
        // captured before it called the guest. Anything else reaching here came from outside that loop and
        // is reported as it stands.
        String message = failure instanceof PublishInspection.InspectionFault
                ? failure.getMessage()
                : PublishInspection.reason(failure);
        LOGGER.warn("A quality inspector threw inspecting " + artifact.path() + " (" + message + "); holding it in "
                + "quarantine (fail-closed) and recording an inspection-failed finding - could not fully screen, so "
                + "it is not served", failure);
        if (meter != null) {
            meter.detected(format);
        }
        unparseable.set("inspector threw: " + message);
        subjects.set(List.of(PublishInspection.pathDerivedSubject(artifact)));
        assessed.remove();
        return Disposition.QUARANTINE;
    }

    /**
     * Hold or refuse an artifact too large to screen, on the operator's own instruction.
     *
     * <p>It records the same inspection finding the fail-closed legs record, under its own code so the two are
     * distinguishable in the ledger: "could not screen this, and here is why" is the same fact whether the cause was
     * a corrupt archive or a size this deployment declines to read, and an operator reviewing the hold needs the
     * reason either way. No gate assessment is stashed, because none ran.
     */
    private Disposition screenOversized(ArtifactDescriptor artifact,
                                        PublishInspection.OversizedArtifactException oversized) {
        LOGGER.warn("Not screening " + artifact.path() + ": " + oversized.getMessage());
        unparseable.set(oversized.getMessage());
        oversizedFinding.set(Boolean.TRUE);
        subjects.set(List.of(PublishInspection.pathDerivedSubject(artifact)));
        assessed.remove();
        return oversized.policy() == QualityInspector.Oversized.QUARANTINE
                ? Disposition.QUARANTINE
                : Disposition.REJECT;
    }

    /** Route an artifact an inspector claimed but could not parse: FAIL CLOSED. A could-not-parse outcome means the
     *  gate's input silently failed to derive - the license and vulnerability dimensions never saw the artifact's real
     *  coordinate - so it must never read as a clean admit (no silent fallback on a correctness-bearing path). The
     *  upload is HELD in quarantine unconditionally, never admitted: "could not fully screen ⇒ do not serve". The hold
     *  is made visible rather than a silent reject - a WARNING naming the path is logged, the unparseable meter is
     *  bumped ({@code jenrepo.gate.unparseable}, tagged by format), and the parse-failure reason is stashed so
     *  {@link #committed} names it in the quarantine log (which artifact, which inspector's message, why) and records a
     *  distinct {@link Finding.Kind#INSPECTION} finding on the coordinate - so an operator sees a scoped reason for the
     *  hold and can investigate or release it. A real artifact that trips a stricter parser is held by design; the
     *  quarantine review/release flow is the operator's override. */
    private Disposition screenMalformed(ArtifactDescriptor artifact, MalformedArtifactException failure,
                                        UnparseableListener meter) {
        String format = artifact.ecosystem() == null ? "none" : artifact.ecosystem();
        LOGGER.warn(
                "Could not parse claimed artifact " + artifact.path() + "; holding it in quarantine (fail-closed) and "
                        + "recording an inspection-failed finding - could not fully screen, so it is not served", failure);
        if (meter != null) {
            meter.detected(format);
        }
        unparseable.set(failure.getMessage() == null ? "unparseable artifact" : failure.getMessage());
        // A path-derived subject so the quarantine log still names the coordinate the hold is on; no gate assessment is
        // stashed (none ran, and none could - the input would not parse), so committed() names the malformed reason
        // from the stashed marker rather than from a verdict's findings.
        subjects.set(List.of(PublishInspection.pathDerivedSubject(artifact)));
        assessed.remove();
        return Disposition.QUARANTINE;
    }

    /** Fail closed when an advisory feed threw mid-assessment (a rate limit, a mirror outage): the gate assesses
     *  through the feed, so its {@code assess} raised rather than returning a verdict. Could-not-fully-screen holds the
     *  upload in quarantine with a legible, scoped reason (the same discipline {@link #screenMalformed} applies to an
     *  unparseable body), never admitting the unscreened bytes as a silent clean and never letting the raw error escape
     *  to the publisher. The inspected subjects (where the body parsed) name the coordinate the hold is on, so the
     *  quarantine log still identifies the held artifact; a path-derived subject stands in when nothing parsed. */
    private Disposition screenFeedFailure(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected,
            RuntimeException failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        LOGGER.warn("Could not fully screen " + artifact.path() + "; an advisory feed failed closed - holding it in "
                + "quarantine (fail-closed) rather than serving unscreened bytes or surfacing a 500", failure);
        feedFailure.set(message);
        subjects.set(inspected.isEmpty() ? List.of(PublishInspection.pathDerivedSubject(artifact)) : inspected);
        assessed.remove();
        return Disposition.QUARANTINE;
    }
}
