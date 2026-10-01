package build.jenesis.repository.dependents.spi;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The read model of one repository's reverse-dependency index, bound to its scoped store by a
 * {@link DependentsQueryProvider}. It answers the blast-radius questions of the console, the CLI and
 * {@code /api/dependents} - "who depends on X" and "which coordinates does the index hold" - each a small-object fetch,
 * never a scan. The dependents reported are the transitive tree the last sweep recorded, so querying a CVE's coordinate
 * yields every artifact that can reach it.
 */
public interface DependentsQuery {

    /** The coordinates whose recorded dependency tree names {@code coordinate}, sorted; empty when none. */
    List<String> dependents(String coordinate) throws IOException;

    /** Every coordinate the index holds a dependent for, sorted. Materialises the whole key set, so a request path
     *  pages through {@link #coordinates(String, int)}; this form is for internal folds that need it. */
    List<String> coordinates() throws IOException;

    /**
     * One bounded page of the coordinates the index holds a dependent for, resumable by {@code cursor}, so a request
     * never sorts the whole key set in heap. The order is a stable, complete enumeration, and {@code cursor} is an
     * opaque token each implementation mints: a caller passes back {@link CoordinatePage#nextCursor()} until it is
     * {@code null}.
     *
     * <p><strong>An index pages its own shards.</strong> The inherited default slices the whole sorted
     * {@link #coordinates()} set through {@link #pageByListing} - materialising the key set once per page - so past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} coordinates it throws, naming the inheriting class and the remedy. A
     * store-backed reader walks its shards one at a time; an in-memory index calls {@link #pageByListing} by name.
     *
     * @param cursor a previous page's {@link CoordinatePage#nextCursor()}, or {@code null}/empty for the first page
     * @param limit the maximum coordinates to return in this page (a non-positive limit yields an empty page)
     * @throws IllegalStateException when the inherited fallback holds more than
     *     {@link ArtifactStore#MAX_INHERITED_CHILDREN} coordinates
     */
    default CoordinatePage coordinates(String cursor, int limit) throws IOException {
        return pageByListing(this, cursor, limit);
    }

    /**
     * Page {@code query} by slicing its sorted {@link #coordinates()} set - the named form of the inherited fallback,
     * for an in-memory key set. Bounded by {@link InheritedBound}.
     *
     * @throws IllegalStateException when the index holds more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *     coordinates
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

    /** One bounded page of a blast-radius enumeration: the coordinates in the index's stable order and the opaque
     *  {@code nextCursor}, or {@code null} on the last page. */
    record CoordinatePage(List<String> coordinates, String nextCursor) {
        public CoordinatePage {
            coordinates = List.copyOf(coordinates);
        }
    }

    /**
     * Whether a lease-guarded sweep has built the index for this repository at least once, even to an empty index:
     * {@code false} only before the first sweep. An empty {@link #coordinates()} reads the same whether nothing depends
     * on anything or the index was never built, so a query surface consults this to answer "not yet built" rather than
     * serve a false-complete empty result.
     *
     * <p>It reads the sweep's completion marker through {@link #builtAt()} - one small object, on every implementation
     * - because only a sweep can record that it committed; the data cannot tell swept-empty from never-built. An
     * implementation keeping no marker inherits an empty {@link #builtAt()} and reports not built, the conservative
     * half.
     */
    default boolean built() throws IOException {
        return builtAt().isPresent();
    }

    /** When the index was last rebuilt for this repository - the stamp a committing sweep writes, so a surface shows
     *  how fresh its blast radius is. A present stamp is {@link #built()}, so the two never disagree; empty renders
     *  "not yet built", and is what an implementation keeping no marker inherits. One small-object read; writing it is
     *  the sweep's job. */
    default Optional<Instant> builtAt() throws IOException {
        return Optional.empty();
    }

    /**
     * The subset of {@code coordinates} - neutral {@code group:name:version}s a vulnerability report keys lines on -
     * that the index holds a dependent for, so a caller ranks exactly those lines as on a build graph. Bounded by the
     * query set, never the graph; an absent or empty index answers "none reachable".
     *
     * <p><strong>There is deliberately no default.</strong> One could only be written over the whole key set, a
     * whole-graph materialisation on a request-path render, so an implementation must answer boundedly - a sharded
     * reader reads only the shards the query set addresses, an in-memory index its own map. Each coordinate goes
     * through {@link #neutralise}, total and idempotent, so a caller handing in a package URL is answered rather than
     * missed.
     */
    Set<String> reachable(Collection<String> coordinates) throws IOException;

    /**
     * One bounded page of the versions whose manifest <em>declares</em> a dependency on the package {@code dependency}
     * - spelled as its ecosystem spells a coordinate, without a version - each with the requirement the manifest
     * states, resumable by the opaque {@code cursor}.
     *
     * <p><strong>A second tier, not a wider blast radius.</strong> {@link #dependents} answers from resolved trees,
     * where a bill of materials names the exact version built against; a manifest states a requirement (a range, a
     * floor, a tag) whose resolution is a client's later decision. So a declaration is reported with its requirement
     * and never joins {@link #reachable}: a vulnerability's count of affected artifacts stays a count of facts.
     *
     * <p>It answers what the index recorded, so a version since deleted or withheld may be listed until the next pass;
     * a surface that discloses names screens each row. An index keeping no declared tier inherits an empty page and an
     * empty {@link #declarationsBuiltAt()}, so it reads as not yet built rather than "nothing declares it".
     *
     * @param cursor a previous page's {@link DeclarationPage#nextCursor()}, or {@code null}/empty for the first page
     * @param limit the maximum declarations to return (a non-positive limit yields an empty page)
     */
    default DeclarationPage declarations(String dependency, String cursor, int limit) throws IOException {
        return new DeclarationPage(List.of(), null);
    }

    /** When the declared tier last completed a pass over every published version, or empty - its own staleness stamp,
     *  since the two tiers are fed by different passes. */
    default Optional<Instant> declarationsBuiltAt() throws IOException {
        return Optional.empty();
    }

    /** One version's declaration of a dependency: the declaring version's ecosystem, coordinate and version, and the
     *  stated requirement - empty where there is none. */
    record Declaration(String ecosystem, String coordinate, String version, String requirement) {
        public Declaration {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            requirement = requirement == null ? "" : requirement;
        }
    }

    /** One bounded page of {@link #declarations} and the opaque cursor to resume after, or {@code null} on the last
     *  page. */
    record DeclarationPage(List<Declaration> declarations, String nextCursor) {
        public DeclarationPage {
            declarations = List.copyOf(declarations);
        }
    }

    /** Best-effort neutralisation of one coordinate: a malformed purl from a hostile or bit-rotted SBOM falls back to
     *  its raw form - matching no report line - rather than failing the whole report. It is the one join between an
     *  SBOM's spelling and a report line's, shared by the shard function and every {@code reachable}. */
    static String neutralise(String coordinate) {
        try {
            return toNeutralCoordinate(coordinate);
        } catch (RuntimeException _) {
            return coordinate;
        }
    }

    /** Map a package URL ({@code pkg:<type>/<namespace...>/<name>@<version>}, optional
     *  {@code ?qualifiers}/{@code #subpath}, percent-encoded segments) back onto {@code group:name:version}, or return
     *  an already-neutral coordinate unchanged. */
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

    /** Percent-decode one purl segment per RFC 3986: {@code %XX} becomes its byte; everything else is copied - a
     *  {@code +} is not a space in a purl, unlike the form encoding {@code URLDecoder} assumes - and a malformed escape
     *  stays literal. */
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
