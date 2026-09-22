package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Severity;

/**
 * The advisory-to-finding mapping shared by every surface that persists or replays feed advisories - the scan
 * sweep, the on-demand vulnerability report, the console - so the two representations never drift: the malicious
 * flag selects the finding kind, the CVE aliases become references, a recorded fixed version rides the
 * {@code fixed} attribute, and the feed's summary is the description the ledger keeps so no display re-fetches it.
 */
public final class AdvisoryFindings {

    private AdvisoryFindings() {
    }

    /** A feed advisory as a structured finding, attributed to the feed that reported it. */
    public static Finding of(AdvisorySource.Advisory advisory, String feed, String provenance, Instant seen) {
        Finding finding = Finding.of(advisory.id(), feed,
                        advisory.malicious() ? Finding.Kind.MALWARE : Finding.Kind.VULNERABILITY,
                        "advisory", advisory.severity() == null ? Severity.NONE : advisory.severity(),
                        advisory.description(), seen)
                .withReferences(advisory.cves())
                .withProvenance(provenance);
        return advisory.fixed() == null ? finding : finding.withAttribute("fixed", advisory.fixed());
    }

    /** A stored finding read back as the advisory it recorded, for the report surfaces that assemble the
     *  vulnerability view from the ledger instead of re-querying the feeds. */
    public static AdvisorySource.Advisory advisory(Finding finding) {
        List<String> cves = new ArrayList<>();
        for (String reference : finding.references()) {
            if (reference.startsWith("CVE-")) {
                cves.add(reference);
            }
        }
        return new AdvisorySource.Advisory(finding.id(), finding.severity(),
                finding.kind() == Finding.Kind.MALWARE, finding.attributes().get("fixed"), cves,
                finding.description());
    }
}
