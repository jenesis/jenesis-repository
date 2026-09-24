package build.jenesis.repository.findings.store;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.bounds.GenerationIndex;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.TraversalException;

/**
 * A durable inverted index over one repository's stored findings, keyed by the selective filter facets - severity,
 * kind, category and source - so a selective {@code /api/findings} query serves a bounded page by seeking the matching
 * facet bucket rather than scanning the whole findings plane and reading every coordinate's metadata document to fill
 * one page.
 *
 * <p><strong>Why.</strong> The live {@link StoreFindings} walk pushes down only a {@code coordinate} (a direct key
 * prefix) or an {@code ecosystem} (a subtree); a filter on any other facet - severity, kind, category, source - has no
 * key representation, so the walk visits every coordinate version and reads its findings section, applying the facet
 * match in memory. On a large repository a sparse selective query (say "every {@code CRITICAL} finding") reads the whole
 * plane to return a small page. This index gives those facets a key push-down.
 *
 * <p><strong>Shape.</strong> A generation directory holds one flat bucket per facet value:
 * {@code findingsfilter/g<gen>/<facet>/<value>/<ordinal>}, where {@code facet} is one of {@code sev}/{@code kind}/
 * {@code cat}/{@code src}, {@code value} is the URL-encoded facet value (category lower-cased, since the filter matches
 * it case-insensitively) and each child body is the whole {@link Findings.Located} (the finding serialized through the
 * shared {@link FindingsSection} row codec). A finding is written into one bucket per facet it carries a value for, so a
 * seek to {@code findingsfilter/g<gen>/sev/CRITICAL} pages exactly the critical findings - {@link ArtifactStore#page a
 * single ordered, seekable, bounded read} - and the read post-filters the remaining facets over that bounded page.
 *
 * <p><strong>Generations.</strong> The generation lifecycle - the fresh generation, the atomic flip, the reclaim of
 * the superseded one and of a crashed orphan - is {@link GenerationIndex}, shared with the health and vulnerability
 * rank
 * indexes. This index is the one that nests (facet then value under a generation), so it passes its own
 * {@link #reclaimGeneration reclaimer} rather than the shared flat one; everything else about generations is the
 * primitive's.
 *
 * <p><strong>Freshness.</strong> The marker records the composite build stamp - the findings scan freshness followed by
 * the {@linkplain Findings#evictions eviction epoch}, so an eviction that did not move the scan stamp still moves the
 * composite and triggers a rebuild. The stamp drives the <em>rebuild</em> decision only (a rebuild whose stamp already
 * matches is a no-op); the <em>read</em> is eventually consistent - it serves the last built generation regardless of the
 * stamp and only ever falls back (to the live {@link StoreFindings} walk) before the very first build.
 */
final class FindingsFilterIndex {

    /** The repository-scope key root the filter index owns - declared in the storage manifest by the persistence module. */
    static final String PREFIX = "findingsfilter";


    /** The four facets a selective filter targets that have no key push-down in the live walk, so the index gives each
     *  one: the {@link Finding} severity, kind, category and source. Category is bucketed lower-cased, since the filter
     *  matches it case-insensitively; the others match exactly. */
    private static final String SEVERITY = "sev";
    private static final String KIND = "kind";
    private static final String CATEGORY = "cat";
    private static final String SOURCE = "src";

    /** Fixed width of a bucket entry's ordinal, so lexicographic order over a bucket's flat child set is insertion (walk)
     *  order and a page resumes strictly after the previous page's last child. */
    private static final int ORDINAL_WIDTH = 9;

    /** The page size the read's bucket scan pulls a bounded window of child names in. */
    private static final int SCAN_PAGE = 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(FindingsFilterIndex.class);

    private final ArtifactStore store;
    private final GenerationIndex index;

    FindingsFilterIndex(ArtifactStore store) {
        this.store = store;
        this.index = new GenerationIndex(store, PREFIX);
    }

    /** Whether a filter is one the index can seek: a selective facet (severity, kind, category or source) is set and no
     *  {@code coordinate} is (a coordinate filter already resolves by a direct key-prefix lookup in the live walk, so the
     *  index would not improve it). An ecosystem-only or bare filter is left to the live walk too - it is dense and stops
     *  at the requested window rather than scanning sparsely. */
    static boolean indexable(Findings.Filter filter) {
        return filter.coordinate() == null
                && (filter.severity() != null || filter.kind() != null
                        || filter.category() != null || filter.source() != null);
    }

    /**
     * Rebuild the index from the ledger's current findings if they have moved since the last build, else do nothing. The
     * findings are streamed (never buffered whole) into a fresh generation, each written into one bucket per facet value
     * it carries; the previous generation and any crashed-rebuild orphan are reclaimed first; the {@code built} marker
     * flip publishes the new generation atomically.
     *
     * @param ledger       the findings to index, streamed through {@link Findings#all(Findings.Filter, Findings.Visitor)}
     * @param currentStamp the composite build stamp - the findings scan freshness followed by the eviction epoch, so an
     *                     eviction that did not move the scan stamp still moves this composite and so triggers a rebuild
     */
    void rebuild(Findings ledger, String currentStamp) throws IOException {
        index.rebuild(currentStamp, generationPrefix -> {
            Map<String, Long> ordinals = new HashMap<>();      // one monotonic counter per bucket, no collisions
            long[] count = {0};
            ledger.all(Findings.Filter.none(), located -> {
                Finding finding = located.finding();
                index(generationPrefix, SEVERITY, finding.severity() == null ? null : finding.severity().name(),
                        located, ordinals);
                index(generationPrefix, KIND, finding.kind() == null ? null : finding.kind().name(), located, ordinals);
                index(generationPrefix, CATEGORY,
                        finding.category() == null ? null : finding.category().toLowerCase(Locale.ROOT), located,
                        ordinals);
                index(generationPrefix, SOURCE, finding.source(), located, ordinals);
                count[0]++;
            });
            return count[0];
        }, this::reclaimGeneration);
    }

    /** Write one located finding into a facet bucket, keyed by the bucket's next ordinal (so entries never collide and a
     *  bucket reads back in insertion order). A blank/absent facet value indexes nothing - the finding is simply not
     *  matchable on that facet. */
    private void index(String generationPrefix, String facet, String value, Findings.Located located,
                       Map<String, Long> ordinals) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        String bucket = generationPrefix + "/" + facet + "/" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        long ordinal = ordinals.merge(bucket, 1L, Long::sum) - 1;
        store.write(bucket + "/" + String.format(Locale.ROOT, "%0" + ORDINAL_WIDTH + "d", ordinal),
                new ByteArrayInputStream(serialize(located)));
    }

    /**
     * One bounded page of the findings a selective filter matches, served from the built index, or {@code null} only when
     * no index has ever been built - the caller then serves the live-walk fallback for that first, pre-build read. The
     * read seeks the bucket for the filter's most selective facet (a single ordered, seekable, bounded read of that
     * bucket's flat child set) and post-filters the remaining facets over it, so it never scans the whole findings plane.
     * Eventually consistent: it serves the last built generation whenever one stands, never falling back on a moved stamp.
     */
    Findings.Page read(Findings.Filter filter, int offset, int limit) throws IOException {
        Optional<GenerationIndex.Marker> marker = index.marker();
        if (marker.isEmpty()) {
            return null;                                        // never built: the caller serves the live-walk fallback
        }
        String bucketPrefix = marker.get().generationPrefix(PREFIX) + "/" + seekBucket(filter);
        int skip = Math.clamp(offset, 0, Findings.MAX_OFFSET);
        int size = Math.max(0, limit);
        List<Findings.Located> window = new ArrayList<>();
        boolean[] more = {false};
        int[] skipped = {0};
        int[] examined = {0};
        try {
            BUCKET.scan(store, bucketPrefix, name -> {
                if (++examined[0] > EXAMINED_CAP) {
                    more[0] = true;                             // the post-filter budget is spent: answer, say more
                    throw FILLED;
                }
                Optional<Findings.Located> located = located(bucketPrefix + "/" + name);
                // Post-filter the remaining facets (and ecosystem) the seek bucket did not push down; a torn index row is
                // skipped rather than blanking the page.
                if (located.isEmpty() || !filter.matches(located.get())) {
                    return;
                }
                if (skipped[0] < skip) {
                    skipped[0]++;
                    return;                                     // page over the rows before the requested offset
                }
                if (window.size() == size) {
                    more[0] = true;                             // one match past the window: more remain
                    throw FILLED;                               // the primitive's cancellation hook: stop the scan here
                }
                window.add(located.get());
            });
        } catch (Filled _) {
            // The window filled and one further match proved more remain - the ordinary end of a satisfied page.
        }
        // The page carries the index's own build-time ledger freshness, not the live scan stamp, so a selective query
        // served from this eventually-consistent index is never shown fresher than the index it came from (Principle 10,
        // the same builtScanStamp split the health/vulnerability rank indexes carry).
        return new Findings.Page(window, more[0], builtScanStamp(marker.get().stamp()));
    }

    /** The bucket scan's bounds. This is a SEARCH WINDOW, not an enumeration: the window is bounded by the caller's
     *  {@code limit}, but the SCAN behind it is not, because a row only counts once the post-filter has accepted it -
     *  so a query whose remaining facets match nothing would walk the whole bucket to prove it. That is what the
     *  {@link #EXAMINED_CAP} examined budget bounds, so the entry cap is off (the window caps the output) and the
     *  examined budget is the binding bound. Reaching it answers the window assembled so far with {@code more=true}
     *  rather than a page assembled from a prefix of the bucket with {@code more=false} - which would tell a caller the
     *  matches were exhausted when they were not. */
    /** The most bucket entries one read examines to fill a window: the facet bucket is seeked, but the remaining
     *  facets are post-filtered, and a sparse combination must not drain a very large bucket for one request. */
    static final int EXAMINED_CAP = 20_000;

    private static final BoundedChildren BUCKET =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(SCAN_PAGE);

    /** The scan-cancellation signal: the window is full and one further match has proved more remain, so no further
     *  round-trip is worth issuing. Thrown from the scan consumer - the cancellation hook {@link BoundedChildren}
     *  documents - and caught immediately at the call site. Stackless and shared: it is control flow, not a failure. */
    private static final class Filled extends IOException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;                        // control flow, not a failure - there is no stack worth capturing
        }
    }

    private static final Filled FILLED = new Filled();

    /** The ledger scan freshness the index was built at - the leading token of the composite build stamp (the findings
     *  scan freshness {@code Instant} string, or blank for a never-scanned repository), the honest as-of instant a
     *  surface renders for the eventually-consistent index rather than the live scan stamp the ledger may have moved
     *  past. The composite is the scan stamp followed by the eviction epoch (see {@link #rebuild}), so the leading
     *  space-delimited token is the scan stamp. */
    private static String builtScanStamp(String stamp) {
        return stamp.isBlank() ? "" : stamp.split(" ", 2)[0];
    }

    /** The bucket to seek for a filter, most-selective facet first: an arbitrary {@code source} or {@code category} value
     *  is typically far more selective than a small-enum {@code kind} or {@code severity}, so seeking it over a combined
     *  filter reads the fewest rows. Any choice is bounded (a bucket is a subset of the plane), so a wrong guess is only
     *  ever slower than the best bucket, never worse than the whole-plane scan this replaces. {@link #indexable} has
     *  already established at least one facet is present. Category is lower-cased to match its case-insensitive filter. */
    private static String seekBucket(Findings.Filter filter) {
        if (filter.source() != null) {
            return SOURCE + "/" + URLEncoder.encode(filter.source(), StandardCharsets.UTF_8);
        }
        if (filter.category() != null) {
            return CATEGORY + "/" + URLEncoder.encode(filter.category().toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
        }
        if (filter.kind() != null) {
            return KIND + "/" + URLEncoder.encode(filter.kind().name(), StandardCharsets.UTF_8);
        }
        return SEVERITY + "/" + URLEncoder.encode(filter.severity().name(), StandardCharsets.UTF_8);
    }

    /** Empty one generation, torn down value-bucket by value-bucket in bounded pages: this index nests facet then
     *  value under a generation, so it hands {@link GenerationIndex} its own reclaimer rather than the flat one. */
    private void reclaimGeneration(String generationPrefix) throws IOException {
        for (String facet : store.list(generationPrefix)) {
            String facetPrefix = generationPrefix + "/" + facet;
            for (String value : store.list(facetPrefix)) {
                index.reclaimFlat(facetPrefix + "/" + value);
            }
        }
    }

    private Optional<Findings.Located> located(String key) {
        if (!store.exists(key)) {
            return Optional.empty();
        }
        try (InputStream in = store.open(key)) {
            JsonNode document = JSON.readTree(in);
            String ecosystem = document.path("ecosystem").asString(null);
            String coordinate = document.path("coordinate").asString(null);
            String version = document.path("version").asString(null);
            if (ecosystem == null || coordinate == null || version == null) {
                return Optional.empty();                        // a torn index row is skipped, never blanks the page
            }
            List<Finding> parsed = FindingsSection.parse(document.path("finding")).recognised();
            if (parsed.isEmpty()) {
                return Optional.empty();                        // a row this generation cannot parse: skip, it rebuilds
            }
            return Optional.of(new Findings.Located(ecosystem, coordinate, version, parsed.getFirst()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Skipping an unreadable findings-filter index row at {}", key, e);
            return Optional.empty();
        }
    }

    /** Serialize a located finding as an index entry: its coordinate plus the finding through the shared
     *  {@link FindingsSection} row codec, so the read reconstructs the exact {@link Findings.Located} without a
     *  re-implementation of the finding's own JSON shape. */
    private static byte[] serialize(Findings.Located located) {
        ObjectNode document = JSON.createObjectNode();
        document.put("ecosystem", located.ecosystem());
        document.put("coordinate", located.coordinate());
        document.put("version", located.version());
        document.set("finding", FindingsSection.serialize(List.of(located.finding()), List.of()));
        return JSON.writeValueAsBytes(document);
    }

    /** The most distinct values one facet listing answers - a facet is a small set (kinds, sources, categories), and
     *  a listing that reached the cap is answered as-is rather than enumerated further. */
    static final int FACET_CAP = 256;

    /** The distinct values of the built generation's facet buckets - the filter choices a console offers - or empty
     *  when no generation has been built. Reads the bucket names only, never an entry. */
    Optional<Findings.Facets> facets() throws IOException {
        Optional<GenerationIndex.Marker> marker = index.marker();
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        String generationPrefix = marker.get().generationPrefix(PREFIX);
        return Optional.of(new Findings.Facets(values(generationPrefix, KIND), values(generationPrefix, SOURCE),
                values(generationPrefix, CATEGORY)));
    }

    private SortedSet<String> values(String generationPrefix, String facet) throws IOException {
        SortedSet<String> values = new TreeSet<>();
        store.page(generationPrefix + "/" + facet, "", FACET_CAP,
                name -> values.add(URLDecoder.decode(name, StandardCharsets.UTF_8)));
        return values;
    }
}
