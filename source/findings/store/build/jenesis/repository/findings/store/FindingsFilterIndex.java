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
 * A durable inverted index over one repository's findings, keyed by severity, kind, category and source, so a selective
 * {@code /api/findings} query reads a bounded page from one facet bucket instead of reading every coordinate's metadata
 * document. The live {@link StoreFindings} walk can push down only a {@code coordinate} (a key prefix) or an
 * {@code ecosystem} (a subtree); the other facets have no key representation.
 *
 * <p><strong>Shape.</strong> {@code findingsfilter/g<gen>/<facet>/<value>/<ordinal>}, where {@code facet} is
 * {@code sev}/{@code kind}/{@code cat}/{@code src}, {@code value} is URL-encoded (category lower-cased, as the filter
 * matches it case-insensitively) and each entry is a whole {@link Findings.Located} through the shared
 * {@link FindingsSection} row codec. A finding is written into one bucket per facet it has a value for, so a seek to
 * {@code findingsfilter/g<gen>/sev/CRITICAL} pages exactly the critical findings in one
 * {@link ArtifactStore#page ordered, bounded read}, and the remaining facets are post-filtered over that page.
 *
 * <p><strong>Generations.</strong> The lifecycle - fresh generation, atomic flip, reclaiming the superseded one and a
 * crashed orphan - is {@link GenerationIndex}'s; this index nests facet then value, so it passes its own
 * {@link #reclaimGeneration reclaimer}.
 *
 * <p><strong>Freshness.</strong> The marker records the composite stamp - scan freshness followed by the
 * {@linkplain Findings#evictions eviction epoch} - so an eviction alone still triggers a rebuild. The stamp decides
 * only whether to rebuild; the read serves the last built generation, falling back to the live walk only before the
 * first build.
 */
final class FindingsFilterIndex {

    /** The repository-scope key root the filter index owns. */
    static final String PREFIX = "findingsfilter";


    /** The four facet bucket names. Category is bucketed lower-cased, since the filter matches it case-insensitively;
     *  the others match exactly. */
    private static final String SEVERITY = "sev";
    private static final String KIND = "kind";
    private static final String CATEGORY = "cat";
    private static final String SOURCE = "src";

    /** Fixed width of a bucket entry's ordinal, so lexicographic order is insertion order and a page resumes strictly
     *  after the previous page's last entry. */
    private static final int ORDINAL_WIDTH = 9;

    /** The page size of the bucket scan. */
    private static final int SCAN_PAGE = 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(FindingsFilterIndex.class);

    private final ArtifactStore store;
    private final GenerationIndex index;

    FindingsFilterIndex(ArtifactStore store) {
        this.store = store;
        this.index = new GenerationIndex(store, PREFIX);
    }

    /** Whether the index can serve a filter: a facet (severity, kind, category or source) is set and no
     *  {@code coordinate} is, since a coordinate resolves by direct key in the live walk. An ecosystem-only or bare
     *  filter is dense, so the live walk stops at its window anyway. */
    static boolean indexable(Findings.Filter filter) {
        return filter.coordinate() == null
                && (filter.severity() != null || filter.kind() != null
                        || filter.category() != null || filter.source() != null);
    }

    /**
     * Rebuild the index if the findings have moved since the last build, else do nothing. The findings stream into a
     * fresh generation, one bucket per facet value; the previous generation and any crashed orphan are reclaimed first;
     * the {@code built} marker flip publishes the new generation atomically.
     *
     * @param ledger the findings to index, streamed through {@link Findings#all(Findings.Filter, Findings.Visitor)}
     * @param currentStamp the composite build stamp - scan freshness followed by the eviction epoch
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

    /** Write one finding into a facet bucket at the bucket's next ordinal. A blank facet value indexes nothing. */
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

    /** One bounded page of the findings a selective filter matches, or {@code null} only when no index has been built,
     *  so the caller serves the live walk. The read seeks the bucket of the filter's most selective facet and
     *  post-filters the rest; it serves the last built generation whenever one stands. */
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
                // Post-filter what the bucket did not push down; a torn index row is skipped rather than blanking the
                // page.
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
            // The window filled and one further match proved more remain.
        }
        // The page carries the index's build-time freshness, not the live scan stamp, so it never claims to be fresher
        // than the index it came from.
        return new Findings.Page(window, more[0], builtScanStamp(marker.get().stamp()));
    }

    /** The most bucket entries one read examines. The window bounds the output, but a row counts only once the
     *  post-filter accepts it, so a sparse facet combination would otherwise walk the whole bucket; this is the binding
     *  bound, and reaching it answers the window so far with {@code more=true}, never a {@code more=false} that claims
     *  exhaustion. */
    static final int EXAMINED_CAP = 20_000;

    private static final BoundedChildren BUCKET =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(SCAN_PAGE);

    /** The scan's cancellation signal: the window is full and one further match proved more remain. Thrown from the
     *  scan consumer, the hook {@link BoundedChildren} documents, and caught at the call site; stackless control
     *  flow. */
    private static final class Filled extends IOException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;                        // control flow, not a failure - there is no stack worth capturing
        }
    }

    private static final Filled FILLED = new Filled();

    /** The scan freshness the index was built at - the leading token of the composite stamp, blank for a never-scanned
     *  repository - which a surface renders as the index's as-of instant. */
    private static String builtScanStamp(String stamp) {
        return stamp.isBlank() ? "" : stamp.split(" ", 2)[0];
    }

    /** The bucket to seek, most selective facet first: a {@code source} or {@code category} value is usually far more
     *  selective than a small-enum {@code kind} or {@code severity}. Any choice is a subset of the plane, so a wrong
     *  guess is only slower. {@link #indexable} has established at least one facet is present. */
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

    /** Empty one generation, value bucket by value bucket in bounded pages - the nested reclaimer
     *  {@link GenerationIndex} is handed. */
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

    /** An index entry: the coordinate plus the finding through the shared {@link FindingsSection} row codec, so the
     *  read reconstructs the exact {@link Findings.Located}. */
    private static byte[] serialize(Findings.Located located) {
        ObjectNode document = JSON.createObjectNode();
        document.put("ecosystem", located.ecosystem());
        document.put("coordinate", located.coordinate());
        document.put("version", located.version());
        document.set("finding", FindingsSection.serialize(List.of(located.finding()), List.of()));
        return JSON.writeValueAsBytes(document);
    }

    /** The most distinct values one facet listing answers; a facet is a small set, so a listing at the cap is answered
     *  as is. */
    static final int FACET_CAP = 256;

    /** The distinct values of the built generation's facet buckets - the filter choices a console offers - or empty
     *  before the first build. Reads bucket names only. */
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
