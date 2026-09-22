package build.jenesis.repository.compliance;

import module java.base;

/**
 * The gate's view of a tenant's active accept-risk waivers: given an advisory the feeds reported against a subject,
 * has an operator recorded a still-standing waiver that accepts that flaw on that coordinate? The {@link ComplianceGate}
 * asks this once per advisory - after {@link Vex} has had its say - and, when a waiver answers, downgrades the finding
 * the advisory would raise to an informational allow that names the waiver and its expiry, rather than holding or
 * rejecting a risk the operator has explicitly and temporarily accepted. A deployment with no waivers (or with the
 * feature disabled) resolves {@link #NONE}, so the gate behaves exactly as before.
 *
 * <p>The seam is a pure query - the waivers themselves are durably recorded as {@code accept-risk} annotations on the
 * findings ledger and only matched here. {@link #of(List)} builds the matcher over a set already filtered to the
 * waivers active at the read instant: among every waiver that both {@linkplain Waiver#covers covers} the advisory and
 * {@linkplain Waiver#appliesTo applies to} the subject, the most recently granted one decides, so a re-granted waiver
 * (a fresh expiry) supersedes an older one for the same coordinate.
 */
@FunctionalInterface
public interface Waivers {

    /** The active waiver that accepts an advisory on a subject, or empty when none does. {@code aliases} are the
     *  advisory's CVE aliases, so a waiver written against the CVE matches a feed that names the flaw by its GHSA id
     *  and vice versa. */
    Optional<Waiver> waived(String ecosystem, String coordinate, String version,
                            String vulnerability, List<String> aliases);

    /** The empty view: no waiver, so nothing is ever accepted - the gate's behaviour when no waiver is recorded or the
     *  feature is disabled. */
    Waivers NONE = (ecosystem, coordinate, version, vulnerability, aliases) -> Optional.empty();

    static Waivers none() {
        return NONE;
    }

    /** A matcher over a fixed set of waivers (already filtered to those active at the read instant). For a queried
     *  (advisory, subject) it takes every waiver that covers the advisory and applies to the subject and lets the most
     *  recently granted one decide, so a re-granted waiver's fresh expiry wins. */
    static Waivers of(List<Waiver> waivers) {
        List<Waiver> snapshot = List.copyOf(waivers);
        return (ecosystem, coordinate, version, vulnerability, aliases) -> {
            Waiver latest = null;
            for (Waiver waiver : snapshot) {
                if (waiver.covers(vulnerability, aliases) && waiver.appliesTo(ecosystem, coordinate, version)
                        && (latest == null || !waiver.when().isBefore(latest.when()))) {
                    latest = waiver;
                }
            }
            return Optional.ofNullable(latest);
        };
    }
}
