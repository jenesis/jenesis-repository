package build.jenesis.repository.search;

import module java.base;

/**
 * A bound query into one repository's full-text index. {@link #search} yields one bounded page of the matching
 * published coordinates - and served paths of path-addressed artifacts - sorted by display string; an empty query pages
 * everything. The index carries each coordinate's declared licences as SPDX id and category, so {@link #search} honours
 * {@code license:<spdx>} and {@code category:<permissive|weak-copyleft|strong-copyleft|network-copyleft|unknown>}
 * tokens - the drill-down from the licence inventory's rows.
 *
 * <h2>Bounded, visibly</h2>
 * The bound is the caller's {@code limit}, clamped by this contract's {@link #MAX_PAGE}, and what remains past a page
 * is named by {@link Hits#nextCursor()}, so a caller resumes or says it stopped; no implementation can hand back a
 * short list indistinguishable from a complete one.
 *
 * <p>"No usable index yet" is an empty {@link Optional}, never {@code null} (clause 3), and it is load-bearing: it
 * makes the caller answer by name rather than render a false-empty result.
 */
public interface SearchQuery {

    /** The most hits one {@link #search} page carries, whatever the caller asks - a ceiling of the contract, so no
     *  index hands back the whole repository. A larger request gets this many and a {@link Hits#nextCursor()}: clamping
     *  is not truncation when the remainder is addressable. */
    int MAX_PAGE = 1_000;

    /**
     * One bounded page of the hits matching {@code query}, sorted by {@link Hit#display()} and resumable through
     * {@link Hits#nextCursor()}; an empty query pages everything. Free-text terms may mix with {@code license:} and
     * {@code category:} tokens, each an additional AND.
     *
     * <p>An empty {@link Optional} when there is no usable index yet - the sweep has not run, or the stored index is a
     * format the reader cannot open - so the caller answers by name. An empty page of a present {@code Optional} is the
     * opposite: a usable index, no match.
     *
     * @param cursor a previous page's {@link Hits#nextCursor()}, or {@code null}/empty for the first page
     * @param limit the most hits to return, clamped to {@link #MAX_PAGE} (a non-positive limit yields an empty page of
     *     a usable index, never the whole set)
     */
    Optional<Hits> search(String query, String cursor, int limit) throws IOException;

    /** One search hit: a published coordinate version, or the served path of an artifact without a coordinate (a raw
     *  upload). A coordinate hit carries ecosystem, coordinate and version; a path hit only its path, beginning with
     *  {@code /}. */
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

        /** What a person reads for this hit and what hits sort by: {@code coordinate:version}, or the served path. */
        public String display() {
            return path != null ? path : coordinate + ":" + version;
        }
    }

    /** One bounded page of hits in display order and the opaque cursor to resume after, present exactly when more
     *  matches remain; a caller passes it back without parsing it. */
    record Hits(List<Hit> hits, Optional<String> nextCursor) {

        public Hits {
            hits = List.copyOf(hits);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }

        /** The last page of a match set: these hits and nothing beyond them. */
        public static Hits last(List<Hit> hits) {
            return new Hits(hits, Optional.empty());
        }

        /** Whether matches remain past this page, so a surface that will not page on says so rather than presenting a
         *  clamped list as the whole answer. */
        public boolean truncated() {
            return nextCursor.isPresent();
        }
    }
}
