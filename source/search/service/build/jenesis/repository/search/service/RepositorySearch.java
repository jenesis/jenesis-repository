package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

/**
 * The one search a repository answers, whichever surface asks: {@code /api/search}, the console's search bar, and the
 * {@code jenrepo} CLI through the first.
 *
 * <p>The repository's {@link SearchMode} decides how. In {@link SearchMode#NAME}, the default, a query is the start of
 * a coordinate's name, looked up in what the repository keeps sorted, then the start of a raw upload's served path, in
 * the same pages. In {@link SearchMode#FULL_TEXT} the installed index answers; a repository whose index is not built
 * yet, or a composition without an index, answers by name and says so rather than rendering a false-empty page.
 *
 * <p>In both modes every hit is screened, so a held version is no more findable than it is served, and every answer is
 * one bounded page with a cursor. The index lags a publish by up to one background pass, so a full-text answer's first
 * page leads with the name lookup's first hits, which is how someone finds what they just published.
 *
 * <p>One instance serves every tenant and repository, holding the index provider so its searchers survive across
 * requests.
 */
public final class RepositorySearch {

    /** The hits a surface shows on one page unless it asks for another number. */
    public static final int PAGE = 100;

    /** How many of the name lookup's hits lead the first page of a full-text answer. */
    static final int LEAD = 10;

    private final Optional<SearchQueryProvider> index;

    /** The search over the installed index, if any. */
    public RepositorySearch() {
        this(SearchQueryProvider.installed());
    }

    /** The search over an explicit index - empty for none - so a caller drives both modes without a
     *  {@code ServiceLoader} registration. */
    public RepositorySearch(Optional<SearchQueryProvider> index) {
        this.index = index;
    }

    /** Whether this composition carries a full-text index at all. */
    public boolean indexInstalled() {
        return index.isPresent();
    }

    /**
     * One bounded page of the hits for {@code query} in the repository whose scoped store is {@code store}.
     *
     * @param scope  the {@code tenant/repository} key the index caches its searcher under
     * @param config the repository's effective configuration, which decides its {@link SearchMode}
     * @param cursor a previous answer's {@link Answer#nextCursor()}, or {@code null} for the first page
     * @param limit  the most hits to return, clamped to {@link SearchQuery#MAX_PAGE}
     * @throws IllegalArgumentException for a cursor no answer handed out
     */
    public Answer search(ArtifactStore store, String scope, UnaryOperator<String> config, String query,
                         String cursor, int limit) throws IOException {
        SearchMode mode = SearchMode.of(config);
        String text = query == null ? "" : query.trim();
        int rows = Math.min(Math.max(0, limit), SearchQuery.MAX_PAGE);
        Cursor from = Cursor.parse(cursor);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        if (mode == SearchMode.FULL_TEXT && index.isPresent() && from.name() == null && from.path() == null) {
            Optional<SearchQuery.Hits> page = index.get().over(store, scope).search(text, from.text(), rows);
            if (page.isPresent()) {
                List<SearchQuery.Hit> hits = new ArrayList<>();
                if (from.text() == null && !text.isEmpty() && rows > 0) {
                    hits.addAll(NameLookup.lookup(inventory, text, null, Math.min(LEAD, rows)).hits());
                }
                for (SearchQuery.Hit hit : page.get().hits()) {
                    if (!hits.contains(hit) && disclosable(inventory, hit)) {
                        hits.add(hit);
                    }
                }
                return new Answer(mode, true, hits, page.get().nextCursor().map(Cursor::text).orElse(null));
            }
        }
        if (from.path() == null) {
            NameLookup.Page page = NameLookup.lookup(inventory, text, from.name(), rows);
            if (page.next() != null) {
                return new Answer(mode, false, page.hits(), Cursor.name(page.next()));
            }
            PathLookup.Page paths = PathLookup.lookup(store, inventory, text, null, rows - page.hits().size());
            List<SearchQuery.Hit> hits = new ArrayList<>(page.hits());
            hits.addAll(paths.hits());
            return new Answer(mode, false, hits, paths.next() == null ? null : Cursor.path(paths.next()));
        }
        PathLookup.Page paths = PathLookup.lookup(store, inventory, text, from.path(), rows);
        return new Answer(mode, false, paths.hits(), paths.next() == null ? null : Cursor.path(paths.next()));
    }

    /** A hit the index holds may since have been withheld or gone; screened as a listing screens it. */
    private static boolean disclosable(StoreRepositoryInventory inventory, SearchQuery.Hit hit) throws IOException {
        return hit.pathAddressed()
                ? inventory.disclosablePath(hit.path(), ServableNames.Policy.HIDE_WITHHELD)
                : inventory.disclosable(hit.ecosystem(), hit.coordinate(), hit.version(),
                        ServableNames.Policy.HIDE_WITHHELD);
    }

    /** One page of an answer: the repository's mode, whether its index answered ({@code false} in
     *  {@link SearchMode#NAME}, and in {@link SearchMode#FULL_TEXT} while the index is unbuilt or not installed), the
     *  hits, and the cursor to the next page, {@code null} when nothing remains. */
    public record Answer(SearchMode mode, boolean indexed, List<SearchQuery.Hit> hits, String nextCursor) {

        public Answer {
            hits = List.copyOf(hits);
        }

        /** Whether more remains past this page. */
        public boolean truncated() {
            return nextCursor != null;
        }
    }

    /** An answer's cursor, saying what answered: a name lookup resumes by name even once the index is built, its path
     *  half by path, an index page in the index. Opaque, URL-safe, and refused when it is not one of these. */
    private record Cursor(NameLookup.Position name, String path, String text) {

        private static final String NAME = "n.";
        private static final String PATH = "p.";
        private static final String TEXT = "t.";
        private static final String SEPARATOR = "\u001f";

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return new Cursor(null, null, null);
            }
            try {
                if (cursor.startsWith(TEXT)) {
                    return new Cursor(null, null, decode(cursor.substring(TEXT.length())));
                }
                if (cursor.startsWith(PATH)) {
                    String path = decode(cursor.substring(PATH.length()));
                    if (path.isEmpty() || path.startsWith("/")) {
                        return new Cursor(null, path, null);
                    }
                }
                if (cursor.startsWith(NAME)) {
                    String[] parts = decode(cursor.substring(NAME.length())).split(SEPARATOR, -1);
                    if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                        return new Cursor(new NameLookup.Position(parts[0], parts[1],
                                parts[2].isEmpty() ? null : parts[2]), null, null);
                    }
                }
            } catch (IllegalArgumentException _) {
                // not base64: refused below, like any cursor this search did not hand out
            }
            throw new IllegalArgumentException("Not a search cursor: " + cursor);
        }

        static String name(NameLookup.Position position) {
            return NAME + encode(position.ecosystem() + SEPARATOR + position.coordinate() + SEPARATOR
                    + (position.version() == null ? "" : position.version()));
        }

        /** Resume the path half strictly after {@code path}, or from its first path for the empty string. */
        static String path(String path) {
            return PATH + encode(path);
        }

        static String text(String cursor) {
            return TEXT + encode(cursor);
        }

        private static String encode(String value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decode(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }
}
