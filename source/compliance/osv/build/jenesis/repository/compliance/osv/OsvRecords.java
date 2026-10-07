package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.feed.Osv;
import us.springett.cvss.Cvss;

/**
 * An OSV record as the product reads one, for every source that answers from OSV's records - asked of the API or read
 * from a mirror of its export: the {@link Advisory} it makes. Severity is the CVSS base score computed from the v2, v3
 * or v4 vector, else GitHub's {@code database_specific.severity} word, else {@link Severity#UNKNOWN} (a malicious
 * record without a score is {@link Severity#NONE}).
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
                OsvQuery.aliasesOf(vuln)));
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
