package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;

/**
 * The ecosystem-neutral facts a policy expression reads about a subject, as named variables ({@code #name}): severity,
 * licence, reachability and the coordinate's metadata. Every value is a plain {@code java.base} type, so the sandbox
 * reads them without reflecting into this module's types - why the dimension needs no {@code opens} and is no
 * code-execution surface. The fixed set is documented on the {@code policy-rules} setting.
 *
 * <p>{@code severity} / {@code severityRank} carry the strongest advisory band (name and ordinal 0..5);
 * {@code reachable} / {@code reachability} / {@code depth} where the subject sits on the build graph;
 * {@code advisories} / {@code advisoryCount} / {@code malicious} the feed findings; {@code licenses} the declared
 * licences; {@code secretCount} / {@code contentScan} the content-scan facts. Every documented variable is always
 * bound, so a rule never trips a null comparison.
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

        // Seeded null, not NONE: NONE is a band a feed reports ("scored zero"), and strongest() prefers a band that
        // says something, so a NONE seed would beat an all-UNKNOWN set and admit what UNKNOWN exists to catch. The fold
        // collapses to NONE below only when nothing was folded in.
        Severity strongest = null;
        List<String> ids = new ArrayList<>();
        boolean malicious = false;
        for (AdvisorySource.Advisory advisory : advisories) {
            ids.add(advisory.id());
            // strongest(), not compareTo: an unscored advisory must not mask a CRITICAL one; an all-unknown set still
            // fails closed, since UNKNOWN outranks every scored band.
            strongest = Severity.strongest(strongest, advisory.severity());
            malicious |= advisory.malicious();
        }
        // No advisories at all is the one honest NONE: nothing found, rather than something unreadable.
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
