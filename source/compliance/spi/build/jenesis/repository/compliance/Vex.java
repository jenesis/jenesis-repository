package build.jenesis.repository.compliance;

import module java.base;

/**
 * The gate's view of a tenant's ingested VEX (Vulnerability Exploitability eXchange) statements: given an advisory the
 * feeds reported against a subject, does a VEX statement mark that vulnerability <em>non-applicable</em> to that
 * product? The {@link ComplianceGate} asks this once per advisory and, when a suppressing statement answers, downgrades
 * the finding the advisory would raise to an informational allow that records which statement cleared it, rather than
 * quarantining or rejecting on a vulnerability the operator has attested does not apply. A deployment with no VEX
 * module resolves {@link #NONE}, so the gate behaves exactly as before.
 *
 * <p>The seam is a pure query - the statements themselves are stored and served by the VEX module over the tenant's
 * store; here they are only matched. {@link #of(List)} builds the matcher: among every statement that both
 * {@linkplain VexStatement#covers covers} the advisory and {@linkplain VexStatement#appliesTo applies to} the subject,
 * the most recent one decides, so a later {@code affected} claim correctly un-suppresses an earlier {@code not_affected}
 * one for the same product.
 */
@FunctionalInterface
public interface Vex {

    /** The VEX statement that marks an advisory non-applicable to a subject, or empty when none does. {@code aliases}
     *  are the advisory's CVE aliases, so a statement written against the CVE matches a feed that names the flaw by its
     *  GHSA id and vice versa. */
    Optional<VexStatement> notApplicable(String ecosystem, String coordinate, String version,
                                         String vulnerability, List<String> aliases);

    /** The empty view: no statement, so nothing is ever suppressed - the gate's behaviour when no VEX is ingested or
     *  the VEX module is absent. */
    Vex NONE = (ecosystem, coordinate, version, vulnerability, aliases) -> Optional.empty();

    static Vex none() {
        return NONE;
    }

    /** A matcher over a fixed set of statements. For a queried (advisory, subject) it takes every statement that covers
     *  the advisory and applies to the subject and lets the most recent one decide: a suppressing status ({@code
     *  not_affected} / {@code fixed}) returns that statement, any other status (or no statement) returns empty. So the
     *  newest claim wins and a re-opened vulnerability stops being suppressed. */
    static Vex of(List<VexStatement> statements) {
        List<VexStatement> snapshot = List.copyOf(statements);
        return (ecosystem, coordinate, version, vulnerability, aliases) -> {
            VexStatement latest = null;
            for (VexStatement statement : snapshot) {
                if (statement.covers(vulnerability, aliases) && statement.appliesTo(ecosystem, coordinate, version)
                        && (latest == null || !statement.when().isBefore(latest.when()))) {
                    latest = statement;
                }
            }
            return latest != null && latest.status().suppresses() ? Optional.of(latest) : Optional.empty();
        };
    }
}
