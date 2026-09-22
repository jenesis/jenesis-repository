package build.jenesis.repository.search;

import module java.base;

/**
 * A bound query into one repository's search index. {@link #search} yields the same {@code coordinate:version} display
 * strings the live substring scan yields, sorted, one bounded page at a time; an empty query pages the indexed
 * coordinates (the browse-everything case). The index also carries each coordinate's declared licenses as a queryable
 * SPDX id and category, so {@link #search} additionally honours {@code license:<spdx>} and {@code category:<permissive|
 * weak-copyleft|strong-copyleft|network-copyleft|unknown>} filter tokens mixed into the query, and {@link #licenses}
 * rolls the whole repository up into per-category and per-SPDX-id facet counts for the license inventory view.
 *
 * <h2>Bounded, and the bound is visible</h2>
 * This surface used to answer {@code List<String> search(String query)} "up to a bounded result cap", with the cap
 * living in the Lucene implementation rather than here. Both halves of that were defects. A caller past the cap got a
 * short list with <em>nothing to distinguish it from a complete one</em> and no way to ask for the rest - the silent
 * truncation binding design gate 4 forbids - and a second implementation was free to honour no cap at all, because the
 * contract stated none. So the bound is now the caller's own {@code limit}, clamped by the {@link #MAX_PAGE} ceiling
 * this contract declares, and what remains past a page is named by {@link Hits#nextCursor()}: a caller either resumes
 * or states plainly that it stopped.
 *
 * <p>The absence signal moved for the same reason. Both legs documented {@code null} as "no usable index yet", which
 * the SPI contract rule forbids outright (clause 3 - {@code null} is never a legal return), and it is load-bearing
 * rather than cosmetic: it is what makes a caller fall back to the live substring scan instead of rendering a
 * false-empty result. An empty {@link Optional} says exactly that, and the compiler makes the caller say which case it
 * is handling.
 */
public interface SearchQuery {

    /**
     * The most coordinates one {@link #search} page may carry, whatever {@code limit} a caller asks for - the ceiling
     * that belongs to the <em>contract</em> rather than to one implementation, so every index answers a page of the
     * same bounded size and none is free to hand back the whole repository. A caller asking for more is served this
     * many and told, through {@link Hits#nextCursor()}, that more remain: clamping is not truncation when the
     * remainder is addressable.
     */
    int MAX_PAGE = 1_000;

    /**
     * One bounded page of the coordinates matching {@code query}, sorted by the {@code coordinate:version} display
     * string and resumable through {@link Hits#nextCursor()}; an empty query pages the indexed coordinates
     * (browse-everything). The query may mix free-text coordinate terms with {@code license:}/{@code category:} filter
     * tokens (each an additional AND constraint).
     *
     * <p>Answers an empty {@link Optional} when this repository has no usable index yet - the sweep has not run, or
     * the stored index is a format the reader cannot open - so the caller falls back to the live substring scan rather
     * than returning an empty result the scan would have filled. Note the fallback scan cannot honour the license
     * filters, which need the index. An <em>empty page</em> of a present {@code Optional} is the opposite answer: the
     * index is usable and nothing matched.
     *
     * @param cursor a previous page's {@link Hits#nextCursor()}, or {@code null}/empty for the first page
     * @param limit  the most coordinates to return, clamped to {@link #MAX_PAGE} (a non-positive limit yields an
     *               empty page of a usable index, never the whole set)
     */
    Optional<Hits> search(String query, String cursor, int limit) throws IOException;

    /**
     * The license inventory facets over this repository's whole index: one {@link LicenseFacet} per distinct license
     * category and per distinct SPDX id, each with the count of coordinates carrying it. Answers an empty
     * {@link Optional} when this repository has no usable index yet (the same signal {@link #search} gives), so the
     * caller can report the inventory as unavailable rather than empty.
     *
     * <p>The row count is bounded by the number of <em>distinct declared license strings</em> in the repository -
     * customer-authored text rather than the SPDX list the name suggests - and by construction it is a roll-up the
     * sweep persists beside the snapshot, so a request never walks the index to build it.
     */
    Optional<List<LicenseFacet>> licenses() throws IOException;

    /**
     * One bounded page of search hits: the matching {@code coordinate:version} display strings in sorted order, and
     * the opaque cursor to resume after. {@code nextCursor} is present exactly when the index holds more matches past
     * this page - so a caller either pages on or renders "more matches remain" - and empty when this page exhausted
     * the match set. A caller never parses the cursor; it passes the previous page's value back.
     */
    record Hits(List<String> coordinates, Optional<String> nextCursor) {

        public Hits {
            coordinates = List.copyOf(coordinates);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }

        /** The last page of a match set: these rows and nothing beyond them. */
        public static Hits last(List<String> coordinates) {
            return new Hits(coordinates, Optional.empty());
        }

        /** Whether matches remain past this page - the visible outcome at the bound, so a surface that will not page
         *  on says so rather than presenting a clamped list as the whole answer. */
        public boolean truncated() {
            return nextCursor.isPresent();
        }
    }
}
