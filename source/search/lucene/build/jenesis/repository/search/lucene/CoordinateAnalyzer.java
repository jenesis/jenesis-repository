package build.jenesis.repository.search.lucene;

import module java.base;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternTokenizer;

/**
 * The analyzers of the index's two text fields, each a {@link PatternTokenizer} and a {@link LowerCaseFilter}, so a
 * query matches however it is cased. A coordinate is split on its separators ({@code :}, {@code .}, {@code /},
 * {@code -}, {@code @}, {@code +} and whitespace), so {@code com.google.guava:guava} and {@code @angular/core} tokenise
 * into their segments, which the standard tokenizer would keep whole. Prose is split on everything not a letter or
 * digit. A query runs through the analyzer of the field it is matched against.
 */
final class CoordinateAnalyzer extends Analyzer {

    private static final Pattern SEPARATORS = Pattern.compile("[:./@+\\s-]+");

    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Pattern splits;

    private CoordinateAnalyzer(Pattern splits) {
        this.splits = splits;
    }

    /** The analyzer of a coordinate - the {@link LuceneSearcher#NAME_FIELD name} field. */
    static Analyzer coordinates() {
        return new CoordinateAnalyzer(SEPARATORS);
    }

    /** The analyzer of prose - the {@link LuceneSearcher#TEXT_FIELD text} field. */
    static Analyzer words() {
        return new CoordinateAnalyzer(NOT_A_WORD);
    }

    /** What a writer indexes with: the prose analyzer for the text field, the coordinate analyzer for the rest. */
    static Analyzer fields() {
        return new PerFieldAnalyzerWrapper(coordinates(), Map.of(LuceneSearcher.TEXT_FIELD, words()));
    }

    @Override
    protected TokenStreamComponents createComponents(String field) {
        Tokenizer source = new PatternTokenizer(splits, -1);
        TokenStream tokens = new LowerCaseFilter(source);
        return new TokenStreamComponents(source, tokens);
    }
}
