package build.jenesis.repository.findings;

import module java.base;

/**
 * The vulnerable-symbol contract shared by everything that names the code an advisory finding is about - fixed
 * here, beside {@link ReachabilityLabels}, so the writers and the reader never drift. Two channels carry symbols:
 * a symbol-aware feed records them authoritatively as the finding's {@link #ATTRIBUTE} (part of the row's own
 * facts, refreshed with every re-scan), and the AI extractor - which only ever <em>refines</em> - attaches them as
 * a {@code (source="ai-symbols", name="symbols")} {@linkplain Finding.Label label}: an addition beside the row that
 * survives a feed re-record, is separately attributed (its sibling {@code model} label names exactly which model
 * answered and where it runs), and replaces only its own prior value on re-extraction. {@link #symbolsOf} folds the
 * two into one reader-side rule: <strong>the feed's attribute always wins</strong>; the AI label is consulted only
 * where no feed named the symbols, so an extraction can sharpen a coarse verdict but never displace authoritative
 * feed data.
 *
 * <p>The symbol spelling is the one the reachability engine parses: a comma- or whitespace-separated list of
 * {@code com.example.Lib} class names and {@code com.example.Lib#method} members. A symbol set that resolves to
 * nothing in the dependency's actual classes widens the reachability test to the whole artifact rather than
 * narrowing it - so a hallucinated or version-mismatched extraction can only ever coarsen a verdict, never
 * quietly clear it.
 */
public final class SymbolLabels {

    private SymbolLabels() {
    }

    /** The attribute key a symbol-aware feed records on an advisory finding - the authoritative channel. */
    public static final String ATTRIBUTE = "symbols";

    /** The label source the AI symbol extractor writes under. */
    public static final String SOURCE = "ai-symbols";

    /** The label name carrying the extracted symbol list. */
    public static final String NAME = "symbols";

    /** The label name carrying the answering model's attribution ({@code LanguageModel.describe()}). */
    public static final String MODEL = "model";

    /** One resolved symbol set: the list itself, whether it came from the feed's own attribute (authoritative)
     *  or from the AI extractor's label, and the extraction confidence ({@code 1.0} for feed data). */
    public record Provided(String symbols, boolean fromFeed, double confidence) {
    }

    /** The symbols provided for an advisory finding - the feed's {@link #ATTRIBUTE} when present, else the AI
     *  extractor's label - or empty when neither names any. */
    public static Optional<Provided> symbolsOf(Finding finding) {
        String attribute = finding.attributes().get(ATTRIBUTE);
        if (attribute != null && !attribute.isBlank()) {
            return Optional.of(new Provided(attribute, true, 1.0));
        }
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name())
                    && label.value() != null && !label.value().isBlank()) {
                return Optional.of(new Provided(label.value(), false, label.confidence()));
            }
        }
        return Optional.empty();
    }
}
