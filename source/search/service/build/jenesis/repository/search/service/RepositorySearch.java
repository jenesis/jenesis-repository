package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.LicenseFacet;
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
 * a coordinate's name, looked up in what the repository already keeps sorted - a bounded page of point reads and a
 * cursor, with no index built or stored. In {@link SearchMode#FULL_TEXT} the installed index answers it; a repository
 * whose index is not built yet, or a composition that carries no index, answers by name meanwhile and says so, rather
 * than rendering a false-empty page.
 *
 * <p>Two things hold in both modes. Every hit is screened before it is shown, so a version the gate holds is no more
 * findable here than it is served. And every answer is one bounded page with the cursor to the next, so a surface
 * either pages on or says plainly that it stopped. The index is refreshed by a background pass, so it lags a publish by
 * up to one pass; the first page of a full-text answer therefore leads with the name lookup's first hits for the same
 * query, which is how someone finds what they have just published.
 *
 * <p>One instance serves every tenant and repository: the index provider is resolved once, here, so its per-repository
 * searchers survive across requests.
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

    /** The search over an explicit index - empty for none - so a caller can drive both modes without a
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
        if (mode == SearchMode.FULL_TEXT && index.isPresent() && from.name() == null) {
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
        NameLookup.Page page = NameLookup.lookup(inventory, text, from.name(), rows);
        return new Answer(mode, false, page.hits(), page.next() == null ? null : Cursor.name(page.next()));
    }

    /**
     * The licence inventory over a repository, counted by its full-text index; empty when the repository's index is
     * off, not built yet, or not installed - the inventory is the index's answer, and without one there is none.
     */
    public Optional<List<LicenseFacet>> licenses(ArtifactStore store, String scope, UnaryOperator<String> config)
            throws IOException {
        if (SearchMode.of(config) != SearchMode.FULL_TEXT || index.isEmpty()) {
            return Optional.empty();
        }
        return index.get().over(store, scope).licenses();
    }

    /** A hit the index holds may since have been withheld, or have gone; screened as a listing screens it. */
    private static boolean disclosable(StoreRepositoryInventory inventory, SearchQuery.Hit hit) throws IOException {
        return hit.pathAddressed()
                ? inventory.disclosablePath(hit.path(), ServableNames.Policy.HIDE_WITHHELD)
                : inventory.disclosable(hit.ecosystem(), hit.coordinate(), hit.version(),
                        ServableNames.Policy.HIDE_WITHHELD);
    }

    /**
     * One page of an answer: the mode the repository is set to, whether its full-text index answered - {@code false}
     * in {@link SearchMode#NAME}, and in {@link SearchMode#FULL_TEXT} while the index is not built or not installed,
     * when the name lookup answered instead - the hits, and the cursor to the next page, {@code null} when nothing
     * remains.
     */
    public record Answer(SearchMode mode, boolean indexed, List<SearchQuery.Hit> hits, String nextCursor) {

        public Answer {
            hits = List.copyOf(hits);
        }

        /** Whether more remains past this page. */
        public boolean truncated() {
            return nextCursor != null;
        }
    }

    /**
     * An answer's cursor, which says which of the two answered it: a name lookup resumes by name even if the index has
     * been built since, and an index page resumes in the index. Opaque to a caller, URL-safe, and refused when it is
     * not one of these.
     */
    private record Cursor(NameLookup.Position name, String text) {

        private static final String NAME = "n.";
        private static final String TEXT = "t.";
        private static final String SEPARATOR = "\u001f";

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return new Cursor(null, null);
            }
            try {
                if (cursor.startsWith(TEXT)) {
                    return new Cursor(null, decode(cursor.substring(TEXT.length())));
                }
                if (cursor.startsWith(NAME)) {
                    String[] parts = decode(cursor.substring(NAME.length())).split(SEPARATOR, -1);
                    if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                        return new Cursor(new NameLookup.Position(parts[0], parts[1],
                                parts[2].isEmpty() ? null : parts[2]), null);
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
