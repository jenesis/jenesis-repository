package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;

/**
 * The ecosystem-neutral facts a policy expression reads about a subject, flattened into the named variables an
 * expression references with {@code #} - severity, licence, reachability and the coordinate's own metadata. Every value
 * is a plain {@code java.base} type (a string, a number, a boolean, a list of strings), so the expression evaluator
 * reads them as sandbox variables without reflecting into any of this module's own types - the reason the policy
 * dimension needs no {@code opens} and cannot be turned into a code-execution surface. The variable set is fixed and
 * documented on the {@code policy-rules} setting, so an operator writes a rule against a stable vocabulary.
 *
 * <p>{@code severity} / {@code severityRank} carry the strongest advisory band across the feed lookup (its name and its
 * ordinal 0..5, so a rule can compare either way); {@code reachable} / {@code reachability} / {@code depth} carry where
 * the subject sits on the build graph; {@code advisories} / {@code advisoryCount} / {@code malicious} summarise the
 * feed findings; {@code licenses} the declared licences; {@code secretCount} / {@code contentScan} the content-scan
 * facts. Every documented variable is always set (never left unbound), so a rule referencing one never trips a
 * null comparison.
 */
final class PolicyInput {

    private PolicyInput() {
    }

    /** Flatten a subject and the gate's shared advisory lookup into the variable bindings a policy expression reads. */
    static Map<String, Object> variables(ComplianceGate.Subject subject, List<AdvisorySource.Advisory> advisories) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("ecosystem", nullToEmpty(subject.ecosystem()));
        variables.put("coordinate", nullToEmpty(subject.coordinate()));
        variables.put("version", nullToEmpty(subject.version()));

        List<String> licenses = new ArrayList<>();
        for (ComplianceGate.DeclaredLicense license : subject.licenses()) {
            if (license.name() != null && !license.name().isBlank()) {
                licenses.add(license.name());
            }
        }
        variables.put("licenses", List.copyOf(licenses));

        // Seeded null, not NONE. NONE is a band a feed can actually report - "scored, and scored zero" - so
        // seeding with it makes the fold unable to tell an empty advisory set from one every source scored clean,
        // and worse: strongest() prefers a band that says something, so a NONE seed beats an all-UNKNOWN set and
        // the floor admits exactly what the unknown band exists to catch. Null is the absence, and the fold
        // collapses to NONE below only when nothing was folded in at all.
        Severity strongest = null;
        List<String> ids = new ArrayList<>();
        boolean malicious = false;
        for (AdvisorySource.Advisory advisory : advisories) {
            ids.add(advisory.id());
            // strongest(), not compareTo: an advisory nobody could score must not mask one scored CRITICAL when
            // the rule reads #severityRank. The floor still fails closed on an all-unknown set, because UNKNOWN
            // outranks every scored band.
            strongest = Severity.strongest(strongest, advisory.severity());
            malicious |= advisory.malicious();
        }
        // No advisories at all is the one honest NONE here: nothing was found, rather than something unreadable.
        Severity band = strongest == null ? Severity.NONE : strongest;
        variables.put("severity", band.name());
        variables.put("severityRank", band.ordinal());
        variables.put("advisories", List.copyOf(ids));
        variables.put("advisoryCount", ids.size());
        variables.put("malicious", malicious);

        ComplianceGate.Reachability reachability = subject.reachability();
        variables.put("reachability", reachability.kind().name());
        variables.put("reachable", reachability.onBuildGraph());
        variables.put("depth", reachability.depth());

        variables.put("secretCount", subject.secrets().size());
        variables.put("contentScan", subject.contentScan());
        return variables;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
