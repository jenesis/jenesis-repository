package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.search.LicenseFacet;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

/**
 * The license inventory facets - a count per distinct license category and per distinct SPDX id - persisted next to a
 * search-index snapshot as a small, line-oriented sidecar object so the {@code /api/licenses} read side never re-walks
 * the whole index per request. The sweep computes the facets once while it is already visiting every release (the
 * read-first bias: the background pass does the work so the reader does not) and commits them keyed by the snapshot
 * generation; the reader loads them once when it swaps a generation in and serves every subsequent request from heap.
 * An index that carries no sidecar is handled by the reader falling back to a one-time walk of the loaded index, so
 * this is derived data with no format bump and no migration.
 */
final class LicenseFacets {

    private static final String HEADER = "jenesis-facets";

    private LicenseFacets() {
    }

    /** The facets in the canonical order the read side serves - categories first, then SPDX ids, each sorted by value
     *  - as a line-oriented, tab-separated document (kind, value, count). Values are category words and SPDX ids,
     *  neither of which carries a tab or newline, so a plain split round-trips them. */
    static byte[] serialize(List<LicenseFacet> facets) {
        StringBuilder document = new StringBuilder(HEADER).append(" 1\n");
        for (LicenseFacet facet : facets) {
            document.append(facet.kind()).append('\t')
                    .append(facet.value()).append('\t')
                    .append(facet.count()).append('\n');
        }
        return document.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Parse a persisted facet sidecar; a blank or malformed line is skipped so a partially written document degrades
     *  to the facets it can read rather than throwing on the request path. */
    static List<LicenseFacet> parse(byte[] bytes) {
        List<LicenseFacet> facets = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith(HEADER)) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) {
                continue;
            }
            try {
                facets.add(new LicenseFacet(parts[0], parts[1], Long.parseLong(parts[2])));
            } catch (NumberFormatException _) {
                // a malformed count: skip this row rather than fail the whole read
            }
        }
        return facets;
    }

    /** Count the facets by walking a loaded index once - the fallback the reader uses for a pre-existing snapshot that
     *  carries no persisted sidecar, and the exact tally the sweep persists. Categories are counted per distinct
     *  {@code category} value and SPDX ids per distinct {@code license_id} value, deduplicated within a document so a
     *  coordinate is counted once per facet value; the result is categories (sorted) then licenses (sorted). */
    static List<LicenseFacet> fromIndex(IndexSearcher searcher) throws IOException {
        // Counted from the index's own terms: one count per distinct category and per distinct licence term, each a
        // term query the searcher answers from the postings and the live-document set, never a read of every stored
        // document to tally its values - a scan of the whole index on every pass with a marker, and at a hundred
        // thousand versions a minute of it.
        Map<String, Long> categories = new TreeMap<>();
        Map<String, Long> spdx = new TreeMap<>();
        StoredFields fields = searcher.storedFields();
        Terms categoryTerms = MultiTerms.getTerms(searcher.getIndexReader(), "category");
        if (categoryTerms != null) {
            TermsEnum terms = categoryTerms.iterator();
            for (BytesRef term = terms.next(); term != null; term = terms.next()) {
                String category = term.utf8ToString();
                long count = searcher.count(new TermQuery(new Term("category", category)));
                if (count > 0) {
                    categories.put(category, count);
                }
            }
        }
        Terms licenseTerms = MultiTerms.getTerms(searcher.getIndexReader(), "license");
        if (licenseTerms != null) {
            TermsEnum terms = licenseTerms.iterator();
            for (BytesRef term = terms.next(); term != null; term = terms.next()) {
                TermQuery query = new TermQuery(new Term("license", term.utf8ToString()));
                long count = searcher.count(query);
                if (count == 0) {
                    continue;
                }
                // The facet shows the SPDX id as declared; the term is its lower-cased filter form, so one live
                // document carrying the term supplies the display id.
                TopDocs one = searcher.search(query, 1);
                String display = term.utf8ToString();
                if (one.scoreDocs.length > 0) {
                    Document document = fields.document(one.scoreDocs[0].doc);
                    for (String id : distinct(document.getValues("license_id"))) {
                        if (id.toLowerCase(Locale.ROOT).equals(term.utf8ToString())) {
                            display = id;
                            break;
                        }
                    }
                }
                spdx.merge(display, count, Long::sum);
            }
        }
        return toFacets(categories, spdx);
    }

    /** Assemble the canonical facet list from the per-value counts the sweep accumulated: categories first, then SPDX
     *  ids, each in the sorted order its {@code TreeMap} already holds. */
    static List<LicenseFacet> toFacets(Map<String, Long> categories, Map<String, Long> spdx) {
        List<LicenseFacet> facets = new ArrayList<>();
        categories.forEach((value, count) -> facets.add(new LicenseFacet(LicenseFacet.CATEGORY, value, count)));
        spdx.forEach((value, count) -> facets.add(new LicenseFacet(LicenseFacet.LICENSE, value, count)));
        return facets;
    }

    private static Set<String> distinct(String[] values) {
        return values == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(values));
    }
}
