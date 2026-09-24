package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
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
 * The retroactive signature sweep: a gate verdict is reached once, when the bytes arrive, so an artifact admitted
 * under yesterday's dials keeps serving under today's - a signature floor raised to {@code STRONG} says nothing
 * about the weak signatures already in the layout, and an untrusted-signature action moved from {@code ALLOW} to
 * {@code QUARANTINE} holds only what arrives next. This pass applies the current dials to what is already
 * published: it walks the inventory, reads the signature summary the gate recorded for each version, judges that
 * summary under the signature policy as it stands now, and holds a version whose recorded outcome or grade the
 * policy would no longer admit - the same {@code signature} hold, the same review queue, the same release and
 * discard as a publish-time hold.
 *
 * <p>It judges the record, never the bytes. Re-verifying every artifact every pass would cost a read of every blob
 * held, and would in any case answer a different question: a signature's cryptographic outcome does not change
 * with a dial. What changes is the verdict a recorded outcome deserves, and the recorded summary carries exactly
 * what the policy reads - the outcome, the grade, the signer, where the material sat. Two things this therefore
 * cannot do, on purpose: it does not re-decide trust (a key admitted or withdrawn since the record was written
 * changes an outcome only through re-verification, which a re-publish or a late sidecar triggers), and it does not
 * re-judge continuity (the signer-changed finding is measured against the history as it stood at publish). A
 * version published before signatures were recorded here has no summary and is left alone: it was never judged,
 * and inventing a judgement would make it indistinguishable from one judged and found wanting.
 *
 * <p>Off unless {@code signature-sweep} is switched on, because its whole purpose is to hold what a tightened
 * dial now refuses, and that can be a great deal of a repository at once; an operator tightening a dial turns it
 * on with the change and reads the review queue. Exclusive, since it writes holds; idempotent, since a hold
 * already placed is converged rather than duplicated; a human's release sticks through the {@code signature}
 * kind's override, so the very findings a reviewer waved through are never re-held, though a further tightening
 * that raises a new finding may hold the version again. Nothing is ever auto-released here: a dial loosened after
 * a hold leaves the hold for a person to lift.
 */
public final class SignatureSweepTask implements MaintenanceTask {

    static final String NAME = "signature-sweep";
    /** The switch: off by default, since a pass whose purpose is mass-holding must be asked for. */
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
        SignaturePolicy policy = SignaturePolicy.from(context.config());
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        QuarantineLog log = new QuarantineLog(store);
        HoldKind kind = SignatureHoldReleaseObserver.KIND;
        long[] held = {0};
        long[] unenforceable = {0};
        // Every version every Nth pass; between, the versions published since the last full one - a tightened dial
        // wants the full pass, which the operator gets by switching the sweep on (no full pass has landed yet).
        IncrementalPasses cadence = IncrementalPasses.over(store, name(), "findings/signature-sweep", context.config());
        cadence.releases(inventory, release -> {
            String eco = release.ecosystem(), coordinate = release.coordinate(), version = release.version();
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
                        paths, reason, subject);
                held[0]++;
                return;
            }
            if (pointerHeld) {
                return;   // held by the publish-time gate or another kind - serving already retracted, leave it
            }
            if (RetroactiveHolds.hold(store, publication, inventory, log, context.now(), eco, coordinate, version,
                    paths, reason, subject, () -> kind.hold(store, eco, coordinate, version, tokens))) {
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
        context.gauge("jenreg.signatures.sweep.unenforceable",
                "Published versions the retroactive signature sweep would hold but cannot enforce because the "
                        + "blobs-namespace format resolved no served path or content hash - a wiring-regression alarm",
                Map.of("tenant", context.tenant(), "repository", context.repository()), unenforceable[0]);
        if (held[0] > 0) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Repository " + context.tenant() + "/" + context.repository()
                    + " is retroactively holding " + held[0] + " version(s) whose recorded signature the current "
                    + "signature dials no longer admit");
        }
        context.gauge("jenreg.signatures.sweep.held",
                "Published versions a repository is retroactively holding because the signature outcome or grade "
                        + "recorded at publish is one the current signature dials no longer admit",
                Map.of("tenant", context.tenant(), "repository", context.repository()), held[0]);
    }

    /** The recorded summary as the signature the policy judges: the outcome and grade it recorded, the signer it
     *  named and where its trust came from, the material's location and what else it stated; nothing about the
     *  key, since nothing is re-read. */
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
