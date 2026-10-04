package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.store.ArtifactStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;

/**
 * The per-repository read side of the search index. The loaded index sits behind a {@code volatile} reference that a
 * query reads lock-free while the refresh window holds; on expiry the manifest's generation token is compared, an
 * unchanged one keeping the loaded index and a changed one streaming the new snapshot in and swapping it whole. A load
 * failure keeps the last-good index and shortens the retry. A superseded reader is left to the garbage collector rather
 * than closed under a concurrent query.
 *
 * <h2>A due refresh does not hold the request</h2>
 *
 * <p>Loading a changed generation fetches every segment file it names, which over an object store is the index's size
 * in round trips. So a request that finds a refresh due asks for one shared load per scope and waits only
 * {@link #FRESH_WAIT} before answering from the generation it holds: a fast store still reads its own writes, and a
 * slow one answers at once from a generation at most a refresh window plus a load old. The first load is the exception:
 * with no generation to answer from, the caller would answer by name, which is not what a full-text repository asked
 * for.
 */
final class LuceneSearcher {

    /** The display string as sorted doc values, so the index itself takes a page in display order; {@code display} is a
     *  {@code StringField} and cannot be sorted on. */
    static final String SORT_FIELD = "display_sort";

    /** Cap on the MUST clauses one query contributes: Lucene throws {@code TooManyClauses} at 1024 clauses, so a
     *  hostile query of many segments would otherwise fail the endpoint. The first {@value} tokens cover every
     *  realistic search. */
    private static final int MAX_QUERY_CLAUSES = 64;

    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(15);

    /** How long a request waits for a due refresh before answering from the generation it holds: long enough for a
     *  local store's load, far short of a client's timeout. */
    private static final Duration FRESH_WAIT = Duration.ofSeconds(2);

    /** Where a background load runs: a virtual thread per load, holding no JVM open. */
    private static final Executor LOADS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("search-index-load-", 0).factory());

    private final Duration ttl;
    private final Analyzer coordinates = CoordinateAnalyzer.coordinates();

    private final Analyzer words = CoordinateAnalyzer.words();

    private volatile Snapshot snapshot;
    private volatile long refreshAt;

    /** The load in flight for this scope, shared by every request that finds the refresh due; null or done when none
     *  is. Guarded by {@link #loads}, not {@code this}, since {@link #refresh} holds {@code this} for the whole
     *  download. */
    private CompletableFuture<Snapshot> loading;

    private final Object loads = new Object();

    LuceneSearcher(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * One loaded generation. Its reader is reference counted: this searcher holds one reference while the snapshot is
     * current and a query holds another for as long as it runs, so a snapshot replaced or evicted under a running
     * query closes once that query finishes - its reader first, then the directory, through the reader's closed
     * listener.
     */
    private record Snapshot(int generation, IndexSearcher searcher, Directory directory) {

        static Snapshot open(int generation, Directory directory) throws IOException {
            DirectoryReader reader = DirectoryReader.open(directory);
            reader.getReaderCacheHelper().addClosedListener(_ -> directory.close());
            return new Snapshot(generation, new IndexSearcher(reader), directory);
        }

        /** Drop the reference this searcher held; the snapshot closes once no query holds one either. */
        void release() {
            try {
                searcher.getIndexReader().decRef();
            } catch (IOException | RuntimeException _) {
                // best-effort: a reader that cannot close holds only its own files
            }
        }
    }

    /**
     * One bounded page of the coordinates matching {@code query}, in display order and resumable by cursor; an empty
     * query pages everything. Empty when no usable index exists yet, so the caller answers by name.
     *
     * <p>The index takes the page: the cursor is an exclusive lower-bound range on {@code display} ANDed onto the
     * query, and the sort is over {@link #SORT_FIELD}. One extra row is requested, so "more remain" is a fact.
     */
    Optional<SearchQuery.Hits> search(ArtifactStore store, String query, String cursor, int limit) throws IOException {
        Snapshot active = current(store);
        if (active == null) {
            return Optional.empty();
        }
        int rows = Math.min(Math.max(0, limit), SearchQuery.MAX_PAGE);
        if (rows == 0) {
            return Optional.of(SearchQuery.Hits.last(List.of()));   // a usable index, an empty page - not "no index"
        }
        IndexSearcher searcher = active.searcher();
        if (!searcher.getIndexReader().tryIncRef()) {
            return Optional.empty();                            // released under this query: answer by name
        }
        try {
            return search(searcher, query, cursor, rows);
        } finally {
            searcher.getIndexReader().decRef();
        }
    }

    /** {@link #search(ArtifactStore, String, String, int)} over a searcher whose reader the caller holds open. */
    private Optional<SearchQuery.Hits> search(IndexSearcher searcher, String query, String cursor, int rows)
            throws IOException {
        // Free text is matched against the coordinate names first, and against the whole text only when no name
        // matches, so a package's name answers that package rather than every coordinate sharing a token. A query
        // without free text runs once.
        TopDocs top = searcher.search(paged(toQuery(query.trim(), NAME_FIELD, coordinates), cursor), rows + 1,
                new Sort(new SortField(SORT_FIELD, SortField.Type.STRING)));
        if (top.scoreDocs.length == 0 && !analyze(freeText(query.trim()), words).isEmpty()) {
            top = searcher.search(paged(toQuery(query.trim(), TEXT_FIELD, words), cursor), rows + 1,
                    new Sort(new SortField(SORT_FIELD, SortField.Type.STRING)));
        }
        List<SearchQuery.Hit> results = new ArrayList<>();
        String last = null;
        var fields = searcher.storedFields();
        for (ScoreDoc hit : top.scoreDocs) {
            if (results.size() == rows) {
                // The extra row proved more remain; resume after the last row served.
                return Optional.of(new SearchQuery.Hits(results, Optional.of(last)));
            }
            Document document = fields.document(hit.doc);
            last = document.get("display");
            results.add(SearchIndexTask.hit(document.get("key"), last));
        }
        return Optional.of(SearchQuery.Hits.last(results));
    }

    private Snapshot current(ArtifactStore store) {
        Snapshot active = snapshot;
        if (active != null && System.currentTimeMillis() < refreshAt) {
            return active;
        }
        if (active == null) {
            return refresh(store);                              // the first load: nothing to answer from yet
        }
        try {
            return load(store).get(FRESH_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            return active;                                      // the load carries on; answer from what is held
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return active;
        } catch (ExecutionException failed) {
            return active;
        }
    }

    /** The one load for this scope: the one in flight, or a new one started in the background. */
    private CompletableFuture<Snapshot> load(ArtifactStore store) {
        synchronized (loads) {
            if (loading == null || loading.isDone()) {
                loading = CompletableFuture.supplyAsync(() -> refresh(store), LOADS);
            }
            return loading;
        }
    }

    private synchronized Snapshot refresh(ArtifactStore store) {
        if (snapshot != null && System.currentTimeMillis() < refreshAt) {
            return snapshot;                                    // another thread refreshed while we waited
        }
        SearchIndex index = new SearchIndex(store);
        try {
            Optional<ArtifactStore.Versioned> stored = index.manifestVersioned();
            if (stored.isEmpty()) {
                replace(null);                                  // no index built yet - the caller answers by name
                refreshAt = System.currentTimeMillis() + ttl.toMillis();
                return null;
            }
            SearchManifest manifest = SearchManifest.parse(stored.get().content());
            if (manifest.format() != SearchIndex.FORMAT) {
                replace(null);                                  // a format this reader cannot open; the sweep rebuilds
                refreshAt = System.currentTimeMillis() + ttl.toMillis();
                return null;
            }
            if (snapshot != null && snapshot.generation() == manifest.generation()) {
                refreshAt = System.currentTimeMillis() + ttl.toMillis();   // unchanged token - no download
                return snapshot;
            }
            replace(Snapshot.open(manifest.generation(), index.openSnapshot(manifest.generation())));   // swap whole
            refreshAt = System.currentTimeMillis() + ttl.toMillis();
            return snapshot;
        } catch (IOException | RuntimeException e) {
            refreshAt = System.currentTimeMillis() + FAILURE_BACKOFF.toMillis();   // keep last-good, retry sooner
            return snapshot;
        }
    }

    /** Make {@code next} the current snapshot and release the one it replaces. */
    private synchronized void replace(Snapshot next) {
        Snapshot replaced = snapshot;
        snapshot = next;
        if (replaced != null && replaced != next) {
            replaced.release();
        }
    }

    /** Release the loaded snapshot when the query cache evicts this scope or the searcher closes; a query still
     *  running on it finishes first, and one arriving after answers by name. */
    void close() {
        refreshAt = 0;
        replace(null);
    }

    /** The bytes the loaded snapshot's file-backed directory occupies, the summed segment lengths, for the cache's byte
     *  weighting. Zero when nothing is loaded or the directory was closed under a concurrent eviction. */
    long residentBytes() {
        Snapshot active = snapshot;
        if (active == null) {
            return 0;
        }
        try {
            long total = 0;
            Directory directory = active.directory();
            for (String file : directory.listAll()) {
                total += directory.fileLength(file);
            }
            return total;
        } catch (IOException | RuntimeException _) {
            return 0;
        }
    }

    /** The query restricted to rows after {@code cursor} in display order. */
    private static Query paged(Query query, String cursor) {
        BooleanQuery.Builder paged = new BooleanQuery.Builder();
        paged.add(query, BooleanClause.Occur.MUST);
        if (cursor != null && !cursor.isEmpty()) {
            // Strictly after the last row of the previous page.
            paged.add(TermRangeQuery.newStringRange("display", cursor, null, false, false), BooleanClause.Occur.MUST);
        }
        return paged.build();
    }

    /** The index field the coordinate names (with their ecosystem) are analysed into. */
    static final String NAME_FIELD = "name";

    /** The index field the whole display text - ecosystem, coordinate and version - is analysed into. */
    static final String TEXT_FIELD = "text";

    /** The query's free-text part: everything that is not a {@code license:}/{@code category:} filter token. */
    private static String freeText(String query) {
        StringBuilder free = new StringBuilder();
        for (String part : query.split("\\s+")) {
            if (!part.isEmpty() && filterField(part) == null) {
                free.append(part).append(' ');
            }
        }
        return free.toString();
    }

    /** Turn the raw query into a Lucene query: {@code license:<spdx>} and {@code category:<class>} tokens become exact
     *  keyword filters, each an AND constraint matched case-insensitively, and the free text matches {@code field} as
     *  prefix terms through {@code analyzer}, so {@code category:permissive commons} finds permissively-licensed
     *  coordinates with a segment starting {@code commons}. Filters alone still apply; an empty query matches all. */
    private Query toQuery(String query, String field, Analyzer analyzer) throws IOException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int clauses = 0;
        boolean emptyFilter = false;
        StringBuilder free = new StringBuilder();
        for (String part : query.trim().split("\\s+")) {
            if (part.isEmpty() || clauses >= MAX_QUERY_CLAUSES) {
                continue;
            }
            String filter = filterField(part);
            if (filter != null) {
                String value = part.substring(part.indexOf(':') + 1).toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    builder.add(new TermQuery(new Term(filter, value)), BooleanClause.Occur.MUST);
                    clauses++;
                } else {
                    // A filter token with no value ("license:") is an empty filter: it matches nothing rather than
                    // everything.
                    emptyFilter = true;
                }
            } else {
                free.append(part).append(' ');
            }
        }
        // The free text matches two ways. Analysed token prefixes: com.acme matches every coordinate with those tokens
        // anywhere. And the whole display as a prefix, which lists a namespace (com.acme:lib, com.acme.util:x) or
        // everything under a path (/raw/notes), walking the display term dictionary in display order. The display
        // prefix is case-sensitive, since display is the coordinate as published; folding it would need its own field.
        String text = free.toString().trim();
        List<String> tokens = analyze(text, analyzer);
        if (!tokens.isEmpty() || !text.isEmpty()) {
            BooleanQuery.Builder matched = new BooleanQuery.Builder();
            if (!tokens.isEmpty()) {
                BooleanQuery.Builder byToken = new BooleanQuery.Builder();
                int tokenClauses = 0;
                for (String token : tokens) {
                    if (clauses + tokenClauses >= MAX_QUERY_CLAUSES) {
                        break;
                    }
                    byToken.add(new PrefixQuery(new Term(field, token)), BooleanClause.Occur.MUST);
                    tokenClauses++;
                }
                if (tokenClauses > 0) {
                    matched.add(byToken.build(), BooleanClause.Occur.SHOULD);
                    clauses += tokenClauses;
                }
            }
            if (!text.isEmpty() && clauses < MAX_QUERY_CLAUSES) {
                matched.add(new PrefixQuery(new Term("display", text)), BooleanClause.Occur.SHOULD);
                clauses++;
            }
            if (clauses > 0) {
                builder.add(matched.build(), BooleanClause.Occur.MUST);
            }
        }
        if (clauses == 0) {
            // An empty query lists everything; a present-but-empty filter matches nothing.
            return emptyFilter ? new MatchNoDocsQuery() : new MatchAllDocsQuery();
        }
        return builder.build();
    }

    /** The index field a {@code field:value} filter token targets ({@code license} or {@code category}), or
     *  {@code null} for a free-text term. */
    private static String filterField(String part) {
        if (part.regionMatches(true, 0, "license:", 0, "license:".length())) {
            return "license";
        }
        if (part.regionMatches(true, 0, "category:", 0, "category:".length())) {
            return "category";
        }
        return null;
    }

    private static List<String> analyze(String text, Analyzer analyzer) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
