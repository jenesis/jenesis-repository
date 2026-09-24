package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.findings.AdvisoryFindings;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.RetroactiveHolds;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * Findings about a stored artifact reported from outside - a scanner run in CI over an image this repository
 * already serves, a code scanner over a published archive - taken exactly as the gate takes its own.
 *
 * <p>The coordinate a publish names is the right question to ask a feed for a language package, and the wrong one
 * for an image, whose base layer carries packages the coordinate never mentions. Reading those out of the bytes is a
 * scanner's job, and a CI job that already runs one has the answer. This is where it hands the answer over: each
 * finding is recorded in the ledger attributed to the scanner that reported it, and the reported advisories are then
 * decided by the gate a publish would have met - the same threshold, action, VEX and waivers, asked about these
 * advisories instead of its feeds. A verdict other than allow withholds the version through
 * {@link RetroactiveHolds}, the one way a published version is held after the fact, so the hold is on the ordinary
 * review queue with the scanner's findings as its reasons and the ordinary release clears it. A version the
 * repository does not serve is refused rather than recorded: a report names something that is here, or it is about
 * nothing.
 *
 * <p>The verdict is taken from the gate's coordinate dimensions only ({@link ComplianceGate#assessUnclaimed}): a
 * report carries advisories, not the content the licence, attestation and secret dimensions read, so running those
 * over a bare coordinate would decide about content nobody inspected.
 *
 * <p>A hold placed here is always {@link Verdict#QUARANTINE}, whatever action decided it. A {@code REJECT} refuses
 * bytes that have not been stored; these were stored and served already, so the most a later verdict can do is
 * withdraw them for review - which is what the retroactive passes do for the same reason.
 */
public final class ReportedFindings {

    /** The provenance a reported finding carries in the ledger, beside the scanner named as its source. */
    public static final String PROVENANCE = "reported";

    private ReportedFindings() {
    }

    /** A scanner's findings about one version the repository serves. */
    public record Report(String source, String ecosystem, String coordinate, String version,
                         List<AdvisorySource.Advisory> advisories) {

        public Report {
            advisories = List.copyOf(advisories);
        }
    }

    /** What a report did: how many findings it recorded, the verdict they reached, whether the version is now
     *  withheld, and the gate's reasons. */
    public record Outcome(int recorded, Verdict verdict, boolean held, List<String> reasons) {

        public Outcome {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Record {@code report} against the version it names in {@code store} and decide it with {@code gate}; empty when
     * the repository serves no such version. {@code ledger} is the repository's findings ledger.
     */
    public static Optional<Outcome> apply(ArtifactStore store, Findings ledger, ComplianceGate gate, Report report,
                                          Instant now) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        List<String> paths = inventory.paths(report.ecosystem(), report.coordinate(), report.version());
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        List<Finding> findings = new ArrayList<>(report.advisories().size());
        for (AdvisorySource.Advisory advisory : report.advisories()) {
            findings.add(AdvisoryFindings.of(advisory, report.source(), PROVENANCE, now));
        }
        ledger.recordAll(report.ecosystem(), report.coordinate(), report.version(), findings);

        ComplianceGate.Subject subject = new ComplianceGate.Subject(report.ecosystem(), report.coordinate(),
                report.version(), List.of());
        ComplianceGate.Assessment assessment = gate
                .advisories(AdvisorySource.of(Map.of(report.coordinate(), report.advisories())))
                .waivers(WaiverLabels.overlayFor(ledger, List.of(subject), now))
                .assessUnclaimed(subject);
        List<String> reasons = assessment.findings().stream()
                .filter(finding -> finding.verdict() != Verdict.ALLOW)
                .map(ComplianceGate.Finding::detail)
                .toList();
        if (assessment.allowed()) {
            return Optional.of(new Outcome(findings.size(), Verdict.ALLOW, false, reasons));
        }
        Publication publication = new Publication(store);
        String reason = "Reported by " + report.source() + ": " + String.join(", ", reasons);
        String held = report.coordinate() + ":" + report.version();
        boolean withheld = RetroactiveHolds.anyHeld(store, paths)
                || RetroactiveHolds.hold(store, publication, inventory, new QuarantineLog(store), now,
                        report.ecosystem(), report.coordinate(), report.version(), paths, reason, held, () -> {
                        });
        return Optional.of(new Outcome(findings.size(), assessment.verdict(), withheld, reasons));
    }
}
