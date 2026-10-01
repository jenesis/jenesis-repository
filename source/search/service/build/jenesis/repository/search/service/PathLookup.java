package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.Trees;

/**
 * A lookup of path-addressed artifacts - a raw upload, which no coordinate names - by the start of the path they are
 * served at, answered from the served-path pointers the store already keeps sorted. It is the name lookup's other
 * half: a coordinate is found by the start of its name, and a file nothing names is found by the start of its path.
 *
 * <p>The query is a path relative to the repository ({@code installers/setup}, with or without a leading slash), as
 * its URL reads it. A format keeps what it serves under its mount ({@code /raw/installers/setup.bin}), which a
 * repository holding that format alone leaves out of its URL, so the query is looked up as typed and under the mount
 * of every format that names no coordinate. Each is one ordered descent of the pointers from the first key that could
 * start with it to the first that could not, so a lookup in a folder of a million files reads the pages that hold the
 * matches and not the rest. Only
 * a path no format describes to a coordinate is a hit - a Maven jar is found by its coordinate, never a second time by
 * its file name - and each is screened as it would be served, so a held file is no more findable here than it is
 * downloadable. The gate's review subtree is never entered.
 *
 * <p>Bounded as the name lookup is: at most the caller's {@code limit} hits, and at most {@link NameLookup#EXAMINED}
 * pointers looked at across the descents to find them, after which the page answers cut short with the path to resume
 * after.
 */
final class PathLookup {

    private PathLookup() {
    }

    /** One page: the disclosed hits, and the served path to resume strictly after - the empty string to start from
     *  the first, which a page of no rows answers when there is a hit for the next, and {@code null} when nothing
     *  remains past it. */
    record Page(List<SearchQuery.Hit> hits, String next) {
    }

    /** One bounded page of the disclosable path-addressed artifacts whose served path starts with {@code prefix},
     *  strictly after the served path {@code after} ({@code null} from the first). */
    static Page lookup(ArtifactStore store, StoreRepositoryInventory inventory, String prefix, String after, int limit)
            throws IOException {
        String path = prefix.startsWith("/") ? prefix.substring(1) : prefix;
        if (!relative(path)) {
            return new Page(List.of(), null);
        }
        String resume = after == null || after.isEmpty() ? null : ServableNames.PUBLISHED + after;
        List<SearchQuery.Hit> hits = new ArrayList<>();
        int examined = 0;
        String seen = after == null ? "" : after;
        for (String start : starts(path)) {
            // A range wholly before the cursor was answered by an earlier page; the ranges are disjoint prefix blocks,
            // so a cursor past a range's start that does not lie in it lies past all of it.
            if (resume != null && !resume.startsWith(start) && Trees.order(resume, start) > 0) {
                continue;
            }
            int wanted = limit - hits.size();
            Range range = new Range(inventory, start, resume != null && resume.startsWith(start) ? resume : null,
                    wanted, NameLookup.EXAMINED - examined);
            boolean exhausted = Trees.descend(store, ServableNames.PUBLISHED, range);
            examined += range.examined;
            seen = range.last == null ? seen : range.last;
            if (range.hits.size() > wanted) {
                hits.addAll(range.hits.subList(0, wanted));
                return new Page(hits, hits.isEmpty() ? "" : hits.getLast().path());
            }
            hits.addAll(range.hits);
            if (!exhausted) {
                return new Page(hits, seen);
            }
        }
        return new Page(hits, null);
    }

    /** The keys the query could start, in the order the store keys them: as typed, and under the mount of every
     *  installed format that names no coordinate - leaving out one that another already covers. */
    private static List<String> starts(String path) {
        List<String> starts = new ArrayList<>();
        starts.add(ServableNames.PUBLISHED + "/" + path);
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            String mount = format.mount();
            if (format.offered() && !(format instanceof ArtifactLayout) && !(format instanceof BlobLayout)
                    && mount.length() > 1) {
                starts.add(ServableNames.PUBLISHED + mount + "/" + path);
            }
        }
        starts.sort(Trees::order);
        List<String> disjoint = new ArrayList<>();
        for (String start : starts) {
            if (disjoint.stream().noneMatch(start::startsWith)) {
                disjoint.add(start);
            }
        }
        return disjoint;
    }

    /** Whether {@code path} could start a served path: no empty segment before its last, and no {@code .} or
     *  {@code ..} - anything else names no stored file, so it matches none rather than reaching the traversal screen
     *  as a key. */
    private static boolean relative(String path) {
        String[] segments = path.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() ? index < segments.length - 1 : segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** The descent's bounds and its progress: keys starting with {@code start}, strictly after {@code resume}. */
    private static final class Range implements Trees.Visitor {

        private final StoreRepositoryInventory inventory;
        private final String start;
        private final String resume;
        private final int limit;
        private final int budget;
        private final List<SearchQuery.Hit> hits = new ArrayList<>();
        private String last;
        private int examined;

        private Range(StoreRepositoryInventory inventory, String start, String resume, int limit, int budget) {
            this.inventory = inventory;
            this.start = start;
            this.resume = resume;
            this.limit = limit;
            this.budget = budget;
        }

        @Override
        public void visit(String key) throws IOException {
            examined++;
            String path = key.substring(ServableNames.PUBLISHED.length());
            last = path;
            if (inventory.pathAddressed(path)
                    && inventory.disclosablePath(path, ServableNames.Policy.HIDE_WITHHELD)) {
                hits.add(SearchQuery.Hit.path(path));
            }
        }

        @Override
        public boolean emits(String key) {
            return key.startsWith(start) && (resume == null || Trees.order(key, resume) > 0);
        }

        @Override
        public boolean enters(String prefix) {
            if (prefix.equals(ServableNames.PUBLISHED + "/" + ServableNames.QUARANTINE)) {
                return false;
            }
            return prefix.startsWith(start) || start.startsWith(prefix + "/");
        }

        @Override
        public String seek() {
            if (resume != null) {
                return resume;
            }
            // The range's start as a key: a query ending at a folder's slash seeks to the folder.
            String key = start.endsWith("/") ? start.substring(0, start.length() - 1) : start;
            return key.equals(ServableNames.PUBLISHED) ? null : key;
        }

        @Override
        public String ceiling() {
            return start + Character.MAX_VALUE;
        }

        @Override
        public boolean proceeds() {
            return hits.size() <= limit && examined < budget;
        }
    }
}
