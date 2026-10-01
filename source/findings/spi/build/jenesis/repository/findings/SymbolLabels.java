package build.jenesis.repository.findings;

import module java.base;

/**
 * The vulnerable-symbol contract: the code an advisory finding is about. A symbol-aware feed records symbols
 * authoritatively as the finding's {@link #ATTRIBUTE}; an AI extractor may add them as a
 * {@code (source="ai-symbols", name="symbols")} {@linkplain Finding.Label label}, which survives a feed re-record.
 * {@link #symbolsOf} prefers the feed's attribute and consults the label only where no feed named symbols.
 *
 * <p>The spelling is the one the reachability engine parses: comma- or whitespace-separated {@code com.example.Lib}
 * classes and {@code com.example.Lib#method} members. A set that resolves to nothing widens the reachability test to
 * the whole artifact, so a wrong extraction can only coarsen a verdict, never clear it.
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
