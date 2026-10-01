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
 * The per-repository read side of the search index, following the {@code CisaKnownExploitedSource} volatile-swap
 * pattern: the loaded index sits behind a {@code volatile} reference, a query on the hot path reads it lock-free while
 * the TTL window holds, and on expiry one thread re-reads the manifest under a lock and compares the generation token
 * - an unchanged token keeps the loaded index untouched (no download), a changed token streams the new snapshot in and
 * swaps it whole. A load failure keeps the last-good index and shortens the retry, so a transient store hiccup never
 * blanks the search box. A superseded reader is left for the garbage collector rather than closed under a possible
 * concurrent query - the snapshot is a file-backed directory of links into the node's segment cache, closed
 * with the reader that opened it.
 *
 * <h2>A due refresh does not hold the request</h2>
 *
 * <p>"One thread re-reads under a lock" was true of the thread and not of the requests. The refresh was
 * {@code synchronized} and ran ON the request that found the window expired, and loading a changed generation means
 * fetching every segment file it names from the store - so that request waited for the whole download, and every
 * other search arriving meanwhile queued on the monitor behind it, while a perfectly good generation sat in memory
 * ready to answer them. Over a directory the download is milliseconds and nobody noticed. Over an object store it is
 * the size of the index in round trips, and the fleet's search-claim scenario spent a night reading it as the
 * emulator's throughput: a bare client timeout on {@code GET /api/search} over Azurite, "a search queued behind a
 * rebuild", which was precisely what it was - queued behind the download that rebuild's cutover had made due.
 *
 * <p>So a request that finds a refresh due asks for ONE load per scope, shared by every request that arrives while
 * it runs, and waits for it only {@link #FRESH_WAIT} before answering from the generation it already holds. A fast
 * store still reads its own writes - the load finishes inside the wait - and a slow one answers at once from the
 * last-good generation, which is at most the refresh window plus a load old: the staleness this class already
 * promised, rather than a request that outlives its client. The FIRST load is the exception, and deliberately: with
 * no generation to answer from, the caller would answer by name, which is not the answer a repository with full-text
 * search on asked for. A superseded or orphaned snapshot a background load publishes is left to the collector like
 * any other.
 */
final class LuceneSearcher {

    /** The field holding the display string as sorted doc values, so a page is taken in display order by the index
     *  itself rather than by sorting a materialised result list. {@code display} is a stored/indexed
     *  {@code StringField} and cannot be sorted on; this is its doc-values twin, written by the same sweep. */
    static final String SORT_FIELD = "display_sort";

    /** Cap on the MUST clauses one query contributes. A query is analyzed into one prefix/term clause per token, and
     *  Lucene's {@code IndexSearcher} throws {@code TooManyClauses} (a {@code RuntimeException}) once a boolean query
     *  reaches its default 1024-clause ceiling; a hostile ~2 KB query of separator-split segments would otherwise 500
     *  the search endpoint. Keeping only the first {@value} tokens matches every realistic search and never trips the
     *  ceiling. */
    private static final int MAX_QUERY_CLAUSES = 64;

    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(15);

    /** How long a request waits for a due refresh before answering from the generation it holds - long enough that a
     *  load over a local store completes inside it, far short of any client's timeout. */
    private static final Duration FRESH_WAIT = Duration.ofSeconds(2);

    /** Where a background load runs: a virtual thread per load, which holds no JVM open and needs no shutdown. */
    private static final Executor LOADS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("search-index-load-", 0).factory());

    private final Duration ttl;
    private final Analyzer coordinates = CoordinateAnalyzer.coordinates();

    private final Analyzer words = CoordinateAnalyzer.words();

    private volatile Snapshot snapshot;
    private volatile long refreshAt;

    /** The load in flight for this scope, shared by every request that finds the refresh due while it runs; null or
     *  done when none is. Guarded by {@link #loads}, NOT by {@code this}: {@link #refresh} holds {@code this} for the
     *  whole download, so a request that took {@code this} to ask for the load would queue behind the very download
     *  it exists to avoid waiting for. */
    private CompletableFuture<Snapshot> loading;

    private final Object loads = new Object();

    LuceneSearcher(Duration ttl) {
        this.ttl = ttl;
    }

    private record Snapshot(int generation, IndexSearcher searcher, Directory directory) {
    }

    /**
     * One bounded page of the coordinates matching {@code query}, in display order and resumable by cursor; an empty
     * query pages everything. Empty {@link Optional} when no usable index exists yet, so the caller answers by name.
     *
     * <p>The page is taken by the <em>index</em>, not by the caller: the cursor becomes an exclusive lower-bound range
     * filter on the {@code display} term ANDed onto the parsed query, and the sort is over
     * {@link #SORT_FIELD}'s doc values. That is what makes the answer a page rather than a slice - asking Lucene for
     * the first {@code MAX_RESULTS} hits in score order and sorting the whole materialised list would hand the caller
     * a silently clamped result with no way to ask for the rest. One extra row is requested so
     * "more remain" is a fact about the index and not a guess from a full page.
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
        // The free text is matched against the coordinate names first, and against the whole text (ecosystem,
        // coordinate and version tokens) only when no name matches: a query that is a package's name answers that
        // package's versions and nothing else, rather than every coordinate whose version happens to share a token
        // - the page would otherwise fill with strangers on a large repository before the named package is reached.
        // Both queries are bounded by the page; a query without free text (filters only, or empty) runs once.
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
                // The probe row proved the index holds more past this page; resume strictly after the last row served.
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

    /** The one load for this scope: the one in flight if there is one, otherwise a new one started in the background. */
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
                snapshot = null;                                // no index built yet - the caller answers by name
                refreshAt = System.currentTimeMillis() + ttl.toMillis();
                return null;
            }
            SearchManifest manifest = SearchManifest.parse(stored.get().content());
            if (manifest.format() != SearchIndex.FORMAT) {
                snapshot = null;                                // a format this reader cannot open; the sweep rebuilds
                refreshAt = System.currentTimeMillis() + ttl.toMillis();
                return null;
            }
            if (snapshot != null && snapshot.generation() == manifest.generation()) {
                refreshAt = System.currentTimeMillis() + ttl.toMillis();   // unchanged token - no download
                return snapshot;
            }
            Directory directory = index.openSnapshot(manifest.generation());
            IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(directory));
            snapshot = new Snapshot(manifest.generation(), searcher, directory);   // swap whole
            refreshAt = System.currentTimeMillis() + ttl.toMillis();
            return snapshot;
        } catch (IOException | RuntimeException e) {
            refreshAt = System.currentTimeMillis() + FAILURE_BACKOFF.toMillis();   // keep last-good, retry sooner
            return snapshot;
        }
    }

    /** Release the loaded snapshot when the query cache evicts this scope, so an idle repository stops pinning its
     *  whole index. Best-effort: the reader and its file-backed directory are closed to free
     *  the buffers promptly, but a scope is only ever evicted when it is idle or the least-recently-used entry, never
     *  one under an active query, so the close races nothing in practice - and should a straggling query still hold the
     *  reference, it degrades that single request to an answer by name rather than corrupting a read. */
    void close() {
        Snapshot active = snapshot;
        snapshot = null;
        refreshAt = 0;
        if (active != null) {
            try {
                active.searcher().getIndexReader().close();
            } catch (IOException | RuntimeException _) {
                // best-effort
            }
            try {
                active.directory().close();
            } catch (IOException | RuntimeException _) {
                // best-effort
            }
        }
    }

    /** The heap the loaded snapshot's in-memory index occupies - the summed segment-file lengths of its
     *  file-backed directory (mapped or read from the node's cache, not heap) - so the query cache can weight
     *  its LRU by real resident size, not entry count
     *  alone. Zero when nothing is loaded yet (a scope resolved but not queried), and best-effort: a directory closed
     *  under a concurrent eviction reports zero rather than throwing. */
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

    /** Turn the raw query into a Lucene query: {@code license:<spdx>} and {@code category:<class>} tokens become exact
     *  keyword filters (an additional AND constraint each, matched case-insensitively against the lower-cased index
     *  terms), and the remaining free text runs through the coordinate analyzer as prefix terms - so
     *  {@code category:permissive commons} finds permissively-licensed coordinates whose segments start with
     *  {@code commons}. A query that is all filters (no free text) still applies them; an empty query matches all. */
    private static Query paged(Query query, String cursor) {
        BooleanQuery.Builder paged = new BooleanQuery.Builder();
        paged.add(query, BooleanClause.Occur.MUST);
        if (cursor != null && !cursor.isEmpty()) {
            // Strictly after the last row of the previous page, in the same order the sort imposes.
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
                    // A filter token with no value (e.g. "license:") is an explicit but empty filter, not a request
                    // for everything. It must never be silently dropped into a match-all below - that would return the
                    // whole repository for a malformed filter query. Record it so the empty result stands.
                    emptyFilter = true;
                }
            } else {
                free.append(part).append(' ');
            }
        }
        // The free text matches two ways, and a hit either way is a hit. Analysed TOKEN prefixes are the older of
        // the two: `com.acme` is split by the coordinate analyser and every token is matched as a prefix, so it
        // finds `org.example:acme-tool:2.0` as readily as `com.acme:lib:1.0`. What it cannot do is list a
        // namespace - the thing an operator actually types a dotted prefix for - because it never looks at where
        // in the display the tokens fall.
        //
        // So the whole DISPLAY is matched as a prefix beside it: `com.acme` lists `com.acme:lib:1.0` and
        // `com.acme.util:x:2.0`, and `/raw/notes` lists everything served beneath that path. It needs no new field
        // and no reindex - `display` is already the unanalysed term the sort field twins, so the prefix walks the
        // term dictionary and the page comes back in display order like any other.
        //
        // Case-sensitive, deliberately and visibly: `display` carries the coordinate as published, and lower-casing
        // it here would match nothing while looking like it should. A case-insensitive prefix wants its own folded
        // field, which is an index-format change rather than a query one.
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
            // No clauses: an empty query lists everything (the default browse), but a present-but-empty filter token
            // matches nothing rather than degrading to the whole repository.
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
