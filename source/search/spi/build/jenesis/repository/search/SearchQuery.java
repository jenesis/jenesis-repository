package build.jenesis.repository.search;

import module java.base;

/**
 * A bound query into one repository's full-text index. {@link #search} yields one bounded page of the published
 * coordinates - and the served paths of path-addressed artifacts - that match, sorted by their display string; an
 * empty query pages everything indexed. The index also carries each coordinate's declared licences as a queryable
 * SPDX id and category, so {@link #search} honours {@code license:<spdx>} and {@code category:<permissive|
 * weak-copyleft|strong-copyleft|network-copyleft|unknown>} filter tokens - the drill-down the licence inventory's
 * rows link to where a repository has its index.
 *
 * <h2>Bounded, and the bound is visible</h2>
 * A cap hidden in an implementation would hand a caller past it a short list with <em>nothing to distinguish it from
 * a complete one</em> and no way to ask for the rest - a silent truncation - and a second implementation would be
 * free to honour no cap at all, because the contract stated none. So the bound is the caller's own {@code limit},
 * clamped by the {@link #MAX_PAGE} ceiling this contract declares, and what remains past a page is named by
 * {@link Hits#nextCursor()}: a caller either resumes or states plainly that it stopped.
 *
 * <p>The absence signal is explicit for the same reason. "No usable index yet" is never {@code null}, which the SPI
 * contract rule forbids outright (clause 3 - {@code null} is never a legal return), and it is load-bearing rather
 * than cosmetic: it is what makes a caller answer by name instead of rendering a false-empty result. An empty
 * {@link Optional} says exactly that, and the compiler makes the caller say which case it is handling.
 */
public interface SearchQuery {

    /**
     * The most hits one {@link #search} page may carry, whatever {@code limit} a caller asks for - the ceiling that
     * belongs to the <em>contract</em> rather than to one implementation, so every index answers a page of the same
     * bounded size and none is free to hand back the whole repository. A caller asking for more is served this many
     * and told, through {@link Hits#nextCursor()}, that more remain: clamping is not truncation when the remainder is
     * addressable.
     */
    int MAX_PAGE = 1_000;

    /**
     * One bounded page of the hits matching {@code query}, sorted by {@link Hit#display()} and resumable through
     * {@link Hits#nextCursor()}; an empty query pages everything indexed. The query may mix free-text terms with
     * {@code license:}/{@code category:} filter tokens (each an additional AND constraint).
     *
     * <p>Answers an empty {@link Optional} when this repository has no usable index yet - the sweep has not run, or
     * the stored index is a format the reader cannot open - so the caller answers by name rather than returning an
     * empty result the index would have filled. An <em>empty page</em> of a present {@code Optional} is the opposite
     * answer: the index is usable and nothing matched.
     *
     * @param cursor a previous page's {@link Hits#nextCursor()}, or {@code null}/empty for the first page
     * @param limit  the most hits to return, clamped to {@link #MAX_PAGE} (a non-positive limit yields an empty page
     *               of a usable index, never the whole set)
     */
    Optional<Hits> search(String query, String cursor, int limit) throws IOException;

    /**
     * One search hit: a published coordinate version, or the served path of an artifact that has no coordinate (a raw
     * upload). A coordinate hit carries its ecosystem, coordinate and version and no path; a path hit carries only
     * its path, which always begins with {@code /}.
     */
    record Hit(String ecosystem, String coordinate, String version, String path) {

        /** A published coordinate version. */
        public static Hit coordinate(String ecosystem, String coordinate, String version) {
            return new Hit(Objects.requireNonNull(ecosystem, "ecosystem"),
                    Objects.requireNonNull(coordinate, "coordinate"), Objects.requireNonNull(version, "version"),
                    null);
        }

        /** A path-addressed artifact, by the request path it is served at. */
        public static Hit path(String path) {
            return new Hit(null, null, null, Objects.requireNonNull(path, "path"));
        }

        /** Whether this hit is a path-addressed artifact rather than a coordinate. */
        public boolean pathAddressed() {
            return path != null;
        }

        /** What a person reads for this hit, and what the hits are sorted by: {@code coordinate:version}, or the
         *  served path. */
        public String display() {
            return path != null ? path : coordinate + ":" + version;
        }
    }

    /**
     * One bounded page of search hits in display order, and the opaque cursor to resume after. {@code nextCursor} is
     * present exactly when more matches remain past this page - so a caller either pages on or renders "more matches
     * remain" - and empty when this page exhausted the match set. A caller never parses the cursor; it passes the
     * previous page's value back.
     */
    record Hits(List<Hit> hits, Optional<String> nextCursor) {

        public Hits {
            hits = List.copyOf(hits);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }

        /** The last page of a match set: these hits and nothing beyond them. */
        public static Hits last(List<Hit> hits) {
            return new Hits(hits, Optional.empty());
        }

        /** Whether matches remain past this page - the visible outcome at the bound, so a surface that will not page
         *  on says so rather than presenting a clamped list as the whole answer. */
        public boolean truncated() {
            return nextCursor.isPresent();
        }
    }
}
