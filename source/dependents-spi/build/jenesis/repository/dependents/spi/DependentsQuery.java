package build.jenesis.repository.dependents.spi;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The read model of one repository's reverse-dependency index, bound to that repository's scoped store by a
 * {@link DependentsQueryProvider}. It answers the two blast-radius questions the console, the CLI and the
 * {@code /api/dependents} endpoint pose - "who depends on X" and "what coordinates does the index hold" - each a
 * single small-object fetch, never a scan of the tree (the read-first bias the product holds to). The dependents a
 * query reports are the transitive tree the index recorded on its last sweep, so a coordinate below a freshly
 * vulnerable one is included: querying a CVE's coordinate yields every artifact it can reach.
 */
public interface DependentsQuery {

    /** The coordinates that depend on {@code coordinate} - every artifact whose recorded dependency tree names it -
     *  sorted, or empty when nothing recorded depends on it. */
    List<String> dependents(String coordinate) throws IOException;

    /** Every coordinate the index holds a dependent for, sorted - the key set a blast-radius view iterates. Materialises
     *  the whole key set in heap and so scales with the reverse-dependency graph; a request path pages through
     *  {@link #coordinates(String, int)} instead, and this whole-set form is for internal folds that genuinely need it. */
    List<String> coordinates() throws IOException;

    /**
     * One bounded page of the coordinates the index holds a dependent for, resumable by {@code cursor} - the paged form
     * of {@link #coordinates()} so a request (the {@code /api/dependents} enumerate-all and the console picker) never
     * buffers and sorts the whole reverse-dependency key set in heap on a very large index. The order is a stable,
     * complete enumeration (the concrete sharded index pages shard-then-coordinate; this default pages the sorted whole
     * set), and the {@code cursor} is an <em>opaque</em> token each implementation mints and interprets - a caller only
     * passes back the previous page's {@link CoordinatePage#nextCursor()} until it is {@code null}, never parses it.
     *
     * <p><strong>An index pages its own shards; the inherited body is a small-index fallback and says so out loud.</strong>
     * The {@code default} delegates to {@link #pageByListing}, which slices the whole sorted {@link #coordinates()}
     * set: it answers the right page, but it materialises the entire reverse-dependency key set to do it - once per
     * page, so a caller paging N pages buffers the graph N times. So it refuses rather than pretending: past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} coordinates it throws an {@link IllegalStateException} naming the
     * inheriting class and the remedy. The store-backed reader overrides it to walk the shards one at a time, holding
     * no more than a single shard plus the page rather than the whole graph; an implementation whose key set genuinely
     * <em>is</em> in memory calls {@link #pageByListing} by name.
     *
     * @param cursor a previous page's {@link CoordinatePage#nextCursor()}, or {@code null}/empty for the first page
     * @param limit  the maximum coordinates to return in this page (a non-positive limit yields an empty page)
     * @throws IllegalStateException when the inherited fallback holds more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} coordinates
     */
    default CoordinatePage coordinates(String cursor, int limit) throws IOException {
        return pageByListing(this, cursor, limit);
    }

    /**
     * Page {@code query} by slicing its whole sorted {@link #coordinates()} set - the explicit, named form of the
     * fallback {@link #coordinates(String, int)} inherits, for an implementation whose key set is already in memory
     * (an in-process index, a fixture) and for which a "native" paging would be this code anyway.
     *
     * <p>It is bounded, and the bound throws: see {@link InheritedBound}, which holds the ceiling and the refusal for
     * every SPI that ships this shape.
     *
     * @throws IllegalStateException when the index holds more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *                               coordinates
     */
    static CoordinatePage pageByListing(DependentsQuery query, String cursor, int limit) throws IOException {
        if (limit <= 0) {
            return new CoordinatePage(List.of(), null);
        }
        List<String> page = new ArrayList<>();
        for (String coordinate : InheritedBound.bounded(query, "coordinates(String, int)", "coordinates()",
                query.coordinates())) {
            if (cursor != null && !cursor.isEmpty() && coordinate.compareTo(cursor) <= 0) {
                continue;                                       // already returned on an earlier page (sorted whole set)
            }
            page.add(coordinate);
            if (page.size() == limit) {
                return new CoordinatePage(page, page.getLast());
            }
        }
        return new CoordinatePage(page, null);                  // the whole set is exhausted
    }

    /** One bounded page of a blast-radius enumeration: the coordinates in this page (in the index's stable order) and
     *  the opaque {@code nextCursor} to resume after, or {@code null} when this is the last page. */
    record CoordinatePage(List<String> coordinates, String nextCursor) {
        public CoordinatePage {
            coordinates = List.copyOf(coordinates);
        }
    }

    /**
     * Whether the reverse-dependency index has been built at least once for this repository - a {@code Lease}-guarded
     * sweep has run and committed its result, <em>even to an empty index</em>. It is {@code false} only before the
     * first sweep: the module is installed but its pass has never run, or has not yet run over a store whose
     * artifacts predate the plugin. The distinction matters because an empty {@link #coordinates()} is ambiguous - it
     * reads identically whether nothing depends on anything (an authoritative, swept-empty answer) or the index was
     * simply never built (a not-yet-derived one). A query surface consults this so it can answer "index not yet
     * built" instead of serving that false-complete empty result as if it were whole.
     *
     * <p><strong>It reads the sweep's completion marker, and nothing else.</strong> "Has a sweep committed" is a fact
     * only a sweep can record, so this asks {@link #builtAt()} - one small object, the same marker whose instant the
     * staleness surface renders - and reports whether a sweep left a stamp there. It is therefore a single
     * small-object existence probe, never a scan, on every implementation and not merely on the store-backed one: the
     * data cannot answer it, and reading the whole coordinate set to ask whether it is empty answers a different
     * question (it cannot tell a swept-empty index from a never-built one, which is the very ambiguity this method
     * exists to resolve) at the cost of the entire graph. An implementation that keeps no completion marker inherits
     * {@link #builtAt()}'s empty and so reports <em>not built</em> - the conservative half: its surface says "not yet
     * built" rather than presenting an underived view as an authoritative one.
     */
    default boolean built() throws IOException {
        return builtAt().isPresent();
    }

    /**
     * The instant the reverse-dependency index was last rebuilt for this repository - the completion stamp a sweep
     * writes when it commits, so a query surface shows how fresh its rendered blast radius is (Principle 10:
     * staleness is visible; the reverse-dependency analogue of the findings ledger's scan stamp). This is the
     * primitive behind {@link #built()}: a present stamp <em>is</em> the built signal, so the two can never disagree.
     * Empty means no sweep has committed here (rendered "not yet built", never as freshly built); it is also what an
     * implementation that keeps no such marker inherits, so such an implementation reads as never-built until it
     * records one. A single small-object read on the read-first path, never a scan; writing it is the scheduled
     * sweep's job.
     */
    default Optional<Instant> builtAt() throws IOException {
        return Optional.empty();
    }

    /**
     * The subset of {@code coordinates} - each an ecosystem-neutral {@code group:name:version} a vulnerability report
     * keys a line on - the reverse-dependency index holds a dependent for, so a caller ranks exactly those lines
     * reachable: the coordinate is <em>on a build graph</em> (some stored artifact resolves it) rather than only
     * scored in the abstract. The returned set is bounded by the query set, never by the graph, and an index-absent
     * or empty answer degrades to "none reachable" - the same graceful degrade the rest of the query surface holds to.
     *
     * <p><strong>There is deliberately no {@code default}, and that is the contract.</strong> A default could
     * only be written over {@link #coordinates()} - the whole reverse-dependency key set, neutralised and filtered -
     * so an implementation that said nothing would inherit a whole-graph materialisation on a <em>request-path
     * render</em>, which is the earlier "the default IS the defect" one seam over. An implementation must therefore say
     * how it answers a bounded question boundedly; the store-backed reader shards on the neutral spelling and reads
     * only the {@code min(k, 256)} shards the query set addresses, and an in-memory index answers from its own map.
     *
     * <p>The caller's coordinates are neutral by construction, but an implementation must still put each through
     * {@link #neutralise} rather than trusting the spelling: it is total and idempotent, so a caller that hands in a
     * package URL is answered rather than silently missed.
     */
    Set<String> reachable(Collection<String> coordinates) throws IOException;

    /** Best-effort neutralisation of one coordinate, so a single malformed purl from a hostile or bit-rotted SBOM
     *  never throws out of {@link #reachable(Collection)} and takes the whole vulnerability / blast-radius report with
     *  it - an un-neutralisable coordinate falls back to its raw form (it simply will not match a report line, which
     *  is the correct degrade, not a 500). It is the join between the spelling an SBOM used and the spelling a report
     *  line keys on, so both the shard function and every {@code reachable} implementation map through this one copy
     *  rather than a drift-prone second. */
    static String neutralise(String coordinate) {
        try {
            return toNeutralCoordinate(coordinate);
        } catch (RuntimeException _) {
            return coordinate;
        }
    }

    /** Map a package URL back onto the {@code group:name:version} a release carries, or return an already-neutral
     *  coordinate unchanged. Purl form: {@code pkg:<type>/<namespace...>/<name>@<version>} with optional
     *  {@code ?qualifiers}/{@code #subpath} and percent-encoded segments. */
    private static String toNeutralCoordinate(String coordinate) {
        if (coordinate == null || !coordinate.startsWith("pkg:")) {
            return coordinate;                                  // already a neutral group:name:version (or blank)
        }
        String body = coordinate.substring("pkg:".length());
        int qualifiers = body.indexOf('?');
        if (qualifiers >= 0) {
            body = body.substring(0, qualifiers);
        }
        int subpath = body.indexOf('#');
        if (subpath >= 0) {
            body = body.substring(0, subpath);
        }
        String version = null;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            version = decode(body.substring(at + 1));
            body = body.substring(0, at);
        }
        String[] segments = body.split("/");
        if (segments.length < 2) {
            return coordinate;                                  // no name segment - not a shape we can neutralise
        }
        String name = decode(segments[segments.length - 1]);    // the last path segment is the artifact name
        StringBuilder namespace = new StringBuilder();          // every segment between the type and the name
        for (int i = 1; i < segments.length - 1; i++) {
            if (!namespace.isEmpty()) {
                namespace.append('/');
            }
            namespace.append(decode(segments[i]));
        }
        StringBuilder neutral = new StringBuilder();
        if (!namespace.isEmpty()) {
            neutral.append(namespace).append(':');
        }
        neutral.append(name);
        if (version != null && !version.isBlank()) {
            neutral.append(':').append(version);
        }
        return neutral.toString();
    }

    /** Percent-decode one purl segment per RFC 3986: {@code %XX} becomes its byte, everything else (a literal
     *  {@code +}, which is <em>not</em> a space in a purl - unlike {@code application/x-www-form-urlencoded} that
     *  {@code URLDecoder} assumes) is copied verbatim, and a malformed escape ({@code %zz}, a trailing {@code %})
     *  is left literal rather than thrown - a hostile SBOM coordinate must not crash the reachable-set walk. */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;                                     // the common case: nothing to decode, keep '+' literal
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                int hi = Character.digit(segment.charAt(i + 1), 16);
                int lo = Character.digit(segment.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                bytes.write(b);                                 // a literal char (incl. a malformed '%') stays verbatim
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
