package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Severity;

/**
 * The advisory-to-finding mapping every surface that persists or replays feed advisories shares: the malicious flag
 * selects the kind, CVE aliases become references, a fixed version rides the {@code fixed} attribute, and the feed's
 * summary is kept as the description so no display re-fetches it.
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

    /**
     * Ask every feed about one version, under the name {@code asked} the databases know it by, and record what each
     * reports under the version's own coordinate and that feed, answering whether any reported anything. Recording is
     * best-effort upserts, so a label already on a row survives and a failed write leaves the version for the sweep's
     * next pass.
     */
    public static boolean record(Findings ledger, SequencedMap<String, AdvisorySource> feeds, String ecosystem,
                                 String coordinate, String version, AdvisorySource.Query asked, String provenance,
                                 Instant now) {
        boolean reported = false;
        for (Map.Entry<String, AdvisorySource> feed : feeds.entrySet()) {
            for (AdvisorySource.Advisory advisory : feed.getValue().advisories(asked.ecosystem(), asked.coordinate(),
                    asked.version())) {
                reported = true;
                try {
                    ledger.record(ecosystem, coordinate, version, of(advisory, feed.getKey(), provenance, now));
                } catch (IOException | RuntimeException _) {
                    // best-effort: the sweep persists the version on its next pass
                }
            }
        }
        return reported;
    }
}
