package build.jenesis.repository.findings;

import module java.base;

/**
 * A finding label whose value is one of three spellings in a fixed order - a strong one, an undecided one and a weak
 * one, such as {@code reachable}, {@code unknown} and {@code not-reachable} - written by one sweep under
 * {@code (source, name)} and read by every surface that badges or filters by it. The verdict a surface shows for an
 * advisory is the strongest its rows carry, so a view merged from several feeds never under-reports, and a finding
 * without the label is undecided, so a triage view never hides what the sweep has not reached.
 *
 * @param source    the label source the sweep writes under.
 * @param name      the label name carrying the verdict.
 * @param strong    the strongest spelling.
 * @param undecided the spelling of an undecided verdict, which a finding without the label also reads as.
 * @param weak      the weakest spelling.
 */
public record OrderedLabel(String source, String name, String strong, String undecided, String weak) {

    /** Whether {@code value} is one of the three spellings (case-insensitively). */
    public boolean valid(String value) {
        return strong.equalsIgnoreCase(value) || undecided.equalsIgnoreCase(value) || weak.equalsIgnoreCase(value);
    }

    /** The verdict a finding carries, lower-cased, or empty when the sweep has not reached it. */
    public Optional<String> verdictOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (source.equals(label.source()) && name.equals(label.name()) && valid(label.value())) {
                return Optional.of(label.value().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /** The stronger of two verdicts; a {@code null} side yields the other. */
    public String strongest(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return rank(left) >= rank(right) ? left : right;
    }

    /** The verdicts of a coordinate's advisory findings, keyed by advisory id and every CVE alias - the identifiers the
     *  vulnerability view de-duplicates by - each holding the strongest among its rows. */
    public Map<String, String> verdicts(List<Finding> findings) {
        Map<String, String> verdicts = new HashMap<>();
        for (Finding finding : findings) {
            Optional<String> verdict = verdictOf(finding);
            if (verdict.isEmpty()) {
                continue;
            }
            verdicts.merge(finding.id(), verdict.get(), this::strongest);
            for (String reference : finding.references()) {
                if (reference.startsWith("CVE-")) {
                    verdicts.merge(reference, verdict.get(), this::strongest);
                }
            }
        }
        return verdicts;
    }

    /** Whether a row whose badge is {@code verdict} (empty when the label is absent, which reads as undecided) passes
     *  the view filter {@code filter}; a blank filter matches everything. */
    public boolean matches(String verdict, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String shown = verdict == null || verdict.isEmpty() ? undecided : verdict;
        return shown.equalsIgnoreCase(filter.trim());
    }

    private int rank(String verdict) {
        if (strong.equalsIgnoreCase(verdict)) {
            return 2;
        }
        return undecided.equalsIgnoreCase(verdict) ? 1 : 0;
    }
}
