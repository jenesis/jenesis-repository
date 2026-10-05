package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.settings.CoreDefaults;

/**
 * The malicious-package side of the gate. A feed marks an advisory {@link AdvisorySource.Advisory#malicious()
 * malicious} when it describes a deliberately harmful publication - a typosquat, a credential stealer, a poisoned
 * release - rather than a flaw in a legitimate artifact. Such an advisory carries no meaningful CVSS score, so the
 * {@link VulnerabilityPolicy}'s severity threshold would let it through; this policy gates it on the malicious flag
 * alone, regardless of score. The action defaults to {@link Verdict#REJECT}, as the vulnerability and deny-list
 * dimensions do - a curated malicious-package record is a more certain signal than a severity score, so it must not
 * refuse less - and an operator softens it to {@link Verdict#QUARANTINE} to hold such a package for review, or to
 * {@link Verdict#ALLOW}.
 *
 * <p><b>This default is the one {@code malware-action} declares, never a second default for one decision.</b> Every
 * composition that ships reads {@code malware-action}, whose declared default is {@code REJECT} in the setting
 * catalogue, in the generated reference and in the properties the server binds - so a different value here would
 * be one no deployment ever runs, described as the product's behaviour. A default belongs with the code that reads
 * it; {@code MalwareActionDefaultTest} holds the three places to one value.
 *
 * <p><b>{@link Verdict#ALLOW} evaluates and permits; it does not switch the dimension off</b>, as for every
 * discovered {@link GatePolicyProvider} dimension; this one is built into the gate rather than discovered, so it
 * holds the rule itself. A flagged advisory is still
 * matched and still reported, as {@code Finding(ALLOW, "Malicious package: <id>")} - the shape
 * {@link ComplianceGate} already uses for a VEX-suppressed or waived advisory. Returning before looking at the
 * advisories would make a permitted package's assessment byte-identical to one no feed flagged, so the findings
 * ledger, the quarantine review log and every report would lose the difference between "this deployment decided to
 * let a known-malicious package through" and "nothing was flagged" - and an incident review could not recover it.
 */
public final class MaliciousPackagePolicy {

    private final Verdict action;

    public MaliciousPackagePolicy() {
        // The one definition: this constructor, the RepositoryProperties field and the setting catalogue row all read
        // CoreDefaults, so the value cannot be moved in one of them alone.
        this(Verdict.valueOf(CoreDefaults.MALWARE_ACTION));
    }

    private MaliciousPackagePolicy(Verdict action) {
        this.action = action;
    }

    public MaliciousPackagePolicy action(Verdict action) {
        return new MaliciousPackagePolicy(action);
    }

    /** The action, as {@link ComplianceGate#policy()} digests it. */
    String describe() {
        return String.valueOf(action);
    }

    List<ComplianceGate.Finding> assess(List<AdvisorySource.Advisory> advisories) {
        // No early return on ALLOW: the verdict decides what this dimension REPORTS, never whether it looks.
        List<ComplianceGate.Finding> findings = new ArrayList<>();
        for (AdvisorySource.Advisory advisory : advisories) {
            if (advisory.malicious()) {
                findings.add(new ComplianceGate.Finding(action, "Malicious package: " + advisory.id()));
            }
        }
        return findings;
    }
}
