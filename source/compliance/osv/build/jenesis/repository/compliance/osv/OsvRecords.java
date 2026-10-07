package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisoryDatabases;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.VulnerabilityRecord;
import build.jenesis.repository.feed.Osv;
import us.springett.cvss.Cvss;

/**
 * An OSV record as the product reads one, for every source that answers from OSV's records - asked of the API or read
 * from a mirror of its export: the {@link Advisory} it makes. Severity is the CVSS base score computed from the v2, v3
 * or v4 vector, else GitHub's {@code database_specific.severity} word, else {@link Severity#UNKNOWN} (a malicious
 * record without a score is {@link Severity#NONE}).
 *
 * <p>What the record says beyond that is its {@link VulnerabilityRecord}: the database that published it, linked to its
 * record on OSV where the database is not one known to publish its own page; each alias as a reference to where it is
 * published; each CVSS vector as a rating with its score; its CWE identifiers; the advisories among its references;
 * and when it was published and last modified.
 */
final class OsvRecords {

    private OsvRecords() {
    }

    /** The advisory a record makes: every record OSV answers, a {@code MAL-} one flagged malicious. */
    static Optional<Advisory> advisory(JsonNode vuln, String coordinate) {
        String id = vuln.path("id").asString(null);
        if (id == null) {
            return Optional.empty();
        }
        boolean malicious = id.startsWith("MAL-");
        return Optional.of(new Advisory(id, severityOf(vuln, malicious), malicious,
                Osv.fixedVersions(vuln, coordinate), OsvQuery.cvesOf(vuln, id), descriptionOf(vuln),
                OsvQuery.aliasesOf(vuln), detailOf(vuln, id)));
    }

    /** What the record says of the vulnerability beyond its identifier, severity and description. */
    static VulnerabilityRecord detailOf(JsonNode vuln, String id) {
        VulnerabilityRecord.Source source = AdvisoryDatabases.source(id)
                .orElseGet(() -> new VulnerabilityRecord.Source("OSV", AdvisoryDatabases.OSV_RECORD + id));
        List<VulnerabilityRecord.Reference> references = new ArrayList<>();
        for (JsonNode alias : vuln.path("aliases")) {
            String named = alias.asString(null);
            if (named != null && !named.isBlank() && !named.equals(id)) {
                references.add(AdvisoryDatabases.reference(named));
            }
        }
        List<VulnerabilityRecord.Rating> ratings = new ArrayList<>();
        for (JsonNode entry : vuln.path("severity")) {
            String vector = entry.path("score").asString(null);
            if (vector != null && !vector.isBlank()) {
                double score = cvss(vector);
                ratings.add(new VulnerabilityRecord.Rating(source, score < 0 ? null : score,
                        score < 0 ? null : Severity.ofScore(score), VulnerabilityRecord.Rating.method(vector),
                        vector.strip()));
            }
        }
        String word = vuln.path("database_specific").path("severity").asString(null);
        if (ratings.isEmpty() && word != null) {
            ratings.add(new VulnerabilityRecord.Rating(source, null, Severity.ofWord(word, Severity.UNKNOWN), "other",
                    null));
        }
        List<Integer> cwes = new ArrayList<>();
        weaknesses(vuln.path("database_specific").path("cwe_ids"), cwes);
        for (JsonNode affected : vuln.path("affected")) {
            weaknesses(affected.path("database_specific").path("cwe_ids"), cwes);
        }
        List<VulnerabilityRecord.Link> advisories = new ArrayList<>();
        for (JsonNode reference : vuln.path("references")) {
            String url = reference.path("url").asString(null);
            if ("ADVISORY".equalsIgnoreCase(reference.path("type").asString("")) && url != null && !url.isBlank()) {
                advisories.add(new VulnerabilityRecord.Link(null, url));
            }
        }
        return new VulnerabilityRecord(source, references, ratings, cwes, advisories,
                instant(vuln.path("published").asString(null)), instant(vuln.path("modified").asString(null)));
    }

    private static void weaknesses(JsonNode ids, List<Integer> into) {
        for (JsonNode id : ids) {
            AdvisoryDatabases.cwe(id.asString(null)).ifPresent(into::add);
        }
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeException notInstant) {
            return null;
        }
    }

    // The summary, else a bounded prefix of the details, so the findings ledger keeps what the advisory says without
    // re-fetching the feed.
    private static String descriptionOf(JsonNode vuln) {
        return Advisory.description(vuln.path("summary").asString(null), vuln.path("details").asString(""));
    }


    private static Severity severityOf(JsonNode vuln, boolean malicious) {
        double highest = -1.0;
        for (JsonNode entry : vuln.path("severity")) {
            String vector = entry.path("score").asString(null);
            if (vector != null) {
                highest = Math.max(highest, cvss(vector));
            }
        }
        if (highest >= 0) {
            return Severity.ofScore(highest);
        }
        String word = vuln.path("database_specific").path("severity").asString(null);
        if (word != null) {
            // An unrecognised word is a vocabulary this source cannot read, not "nothing severe".
            return Severity.ofWord(word, Severity.UNKNOWN);
        }
        // A malicious-package record (OpenSSF MAL-) carries no score by design: its verdict is the malicious flag.
        // Banded UNKNOWN it would outrank CRITICAL and a severity floor would reject what the gate's rule quarantines.
        if (malicious) {
            return Severity.NONE;
        }
        // Severity vectors none of which scored (a vector in no CVSS version the scorer reads), or no severity at all:
        // unknown, since NONE would be the clean answer clause 4 forbids and a reject-at-or-above floor would admit a
        // critical advisory. NONE means a feed scored it zero (ofScore(0.0) above).
        return Severity.UNKNOWN;
    }

    // The base score of a CVSS v2, v3.0, v3.1 or v4.0 vector, or -1 for one that does not parse as any of them, which
    // leaves the advisory UNKNOWN rather than scored.
    private static double cvss(String vector) {
        try {
            Cvss parsed = Cvss.fromVector(vector.trim());
            return parsed == null ? -1.0 : parsed.calculateScore().getBaseScore();
        } catch (RuntimeException unreadable) {
            return -1.0;
        }
    }
}
