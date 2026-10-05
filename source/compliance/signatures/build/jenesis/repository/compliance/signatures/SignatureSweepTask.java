package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.RetroactiveHolds;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.SignatureSummaries;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * The retroactive signature sweep: applies the current dials to what is already held, since a verdict is reached once,
 * when the bytes arrive. It walks the inventory, judges each version's recorded signature summary under the policy as
 * it stands - a release through the publish flavour, a copy cached from an upstream through the proxy one, whose
 * missing-signature dial is its own - and holds a version the policy would no longer admit, under the ordinary
 * {@code signature} hold.
 *
 * <p>It judges the record, never the bytes: a cryptographic outcome does not change with a dial. So it neither
 * re-decides trust nor re-judges continuity, which need re-verification (a re-publish or a late sidecar), and a version
 * with no recorded summary is left alone.
 *
 * <p>Off unless {@code signature-sweep} is on, since it can hold much of a repository at once. Exclusive and
 * idempotent; a release sticks through the kind's override, and nothing is ever auto-released.
 */
public final class SignatureSweepTask implements MaintenanceTask {

    static final String NAME = "signature-sweep";
    /** The switch, off by default. */
    static final String ENABLED = "signature-sweep";
    static final IntervalSetting INTERVAL = IntervalSetting.of("signature-sweep-interval", "P1D");

    private static final System.Logger LOGGER = System.getLogger(SignatureSweepTask.class.getName());

    private final Duration interval;

    SignatureSweepTask(Duration interval) {
        this.interval = interval;
    }

    static boolean enabled(UnaryOperator<String> config) {
        String flag = config == null ? null : config.apply(ENABLED);
        return flag != null && "true".equalsIgnoreCase(flag.trim());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        if (!enabled(context.config())) {
            return;   // switched off since it was scheduled: write nothing this pass
        }
        ArtifactStore store = context.store();
        SignaturePolicy published = SignaturePolicy.from(context.config());
        SignaturePolicy proxied = published.onProxy();
        // A repository marking its upstreams internal has its copies judged as a version published here is.
        boolean internal = GatePolicyProvider.Path.internal(context.config());
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        QuarantineLog log = new QuarantineLog(store);
        HoldKind kind = SignatureHoldReleaseObserver.KIND;
        long[] held = {0};
        long[] unenforceable = {0};
        // Every version every Nth pass, the versions published or cached since between; the first pass is full.
        IncrementalPasses cadence = IncrementalPasses.over(store, name(), "findings/signature-sweep", context.config());
        cadence.eachHolding(inventory, holding -> {
            String eco = holding.ecosystem(), coordinate = holding.coordinate(), version = holding.version();
            SignaturePolicy policy = holding.cached() && !internal ? proxied : published;
            Optional<SignatureSection.Summary> summary = SignatureSummaries.of(store, eco, coordinate, version);
            if (summary.isEmpty()) {
                return;   // never judged: nothing to re-judge
            }
            List<ComplianceGate.Finding> findings = policy.assess(new ComplianceGate.Subject(eco, coordinate, version,
                    List.of()).withSignatures(List.of(recorded(summary.get(), coordinate + ":" + version))), List.of());
            Set<String> tokens = new LinkedHashSet<>();
            List<String> reasons = new ArrayList<>();
            for (ComplianceGate.Finding finding : findings) {
                if (finding.verdict() != Verdict.ALLOW) {
                    reasons.add(finding.detail());
                    if (finding.hold() != null) {
                        tokens.addAll(finding.hold().subjects());
                    }
                }
            }
            if (reasons.isEmpty()) {
                return;   // the current dials admit what was recorded
            }
            List<String> paths = inventory.paths(eco, coordinate, version);
            boolean pointerHeld = RetroactiveHolds.anyHeld(store, paths);
            Optional<Set<String>> record = kind.held(store, eco, coordinate, version);
            Set<String> overridden = kind.overridden(store, eco, coordinate, version);
            if (overridden.containsAll(tokens)) {
                return;   // every finding the dials raise was released by a human - do not re-hold
            }
            String reason = "signature retroactive: " + String.join("; ", reasons);
            String subject = coordinate + ":" + version;
            if (record.isPresent() && pointerHeld) {
                if (!record.get().containsAll(tokens)) {
                    kind.hold(store, eco, coordinate, version, tokens);   // unions: a further tightening adds its finding
                }
                RetroactiveHolds.converge(store, publication, inventory, log, context.now(), eco, coordinate, version,
                        paths, new RetroactiveHolds.Grounds(SignaturePolicy.RULE, subject, List.of(reason)));
                held[0]++;
                return;
            }
            if (pointerHeld) {
                return;   // held by the publish-time gate or another kind - serving already retracted, leave it
            }
            if (RetroactiveHolds.hold(store, publication, inventory, log, context.now(), eco, coordinate, version,
                    paths, new RetroactiveHolds.Grounds(SignaturePolicy.RULE, subject, List.of(reason)),
                    () -> kind.hold(store, eco, coordinate, version, tokens))) {
                held[0]++;
            } else if (inventory.servesFromBlobs(eco)) {
                unenforceable[0]++;
                LOGGER.log(System.Logger.Level.WARNING, () -> "Repository " + context.tenant() + "/"
                        + context.repository() + " cannot enforce a retroactive signature hold on " + eco + " "
                        + coordinate + ":" + version + " - the ecosystem serves from the blobs namespace but the "
                        + "version resolved no served path or content hash (blobKeys/servedPaths unwired, or the "
                        + "version was evicted); serving is NOT retracted");
            }
        });
        cadence.completed(context.now(), true);
        context.gauge("jenrepo.signatures.sweep.unenforceable",
                "Versions the retroactive signature sweep would hold but cannot enforce because the "
                        + "blobs-namespace format resolved no served path or content hash - a wiring-regression alarm",
                Map.of("tenant", context.tenant(), "repository", context.repository()), unenforceable[0]);
        if (held[0] > 0) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Repository " + context.tenant() + "/" + context.repository()
                    + " is retroactively holding " + held[0] + " version(s) whose recorded signature the current "
                    + "signature dials no longer admit");
        }
        context.gauge("jenrepo.signatures.sweep.held",
                "Versions a repository is retroactively holding because the signature outcome or grade recorded "
                        + "when they arrived is one the current signature dials no longer admit",
                Map.of("tenant", context.tenant(), "repository", context.repository()), held[0]);
    }

    /** The recorded summary as the signature the policy judges. */
    static ComplianceGate.Signature recorded(SignatureSection.Summary summary, String coveredPath) {
        ComplianceGate.Signature.Outcome outcome;
        try {
            outcome = ComplianceGate.Signature.Outcome.valueOf(summary.outcome());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            outcome = ComplianceGate.Signature.Outcome.UNREADABLE;   // a record this deployment cannot read
        }
        SignatureQuality.Grade grade = SignatureQuality.Grade.UNASSESSED;
        if (summary.grade() != null) {
            for (SignatureQuality.Grade candidate : SignatureQuality.Grade.values()) {
                if (candidate.name().equalsIgnoreCase(summary.grade())) {
                    grade = candidate;
                }
            }
        }
        SignerIdentity signer = summary.signer() == null ? null : SignerIdentity.ofWire(summary.signer()).orElse(null);
        return new ComplianceGate.Signature(coveredPath, "recorded", outcome, signer, null, 0, null, null, null,
                new SignatureQuality(grade, List.of()), summary.location() == null ? "" : summary.location(), null,
                summary.source(), summary.details());
    }
}
