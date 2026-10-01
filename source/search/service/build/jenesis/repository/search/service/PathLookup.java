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
 * A lookup of path-addressed artifacts - raw uploads no coordinate names - by the start of their served path, from the
 * served-path pointers the store keeps sorted: the name lookup's other half.
 *
 * <p>The query is a path relative to the repository ({@code installers/setup}, with or without a leading slash). A
 * format keeps what it serves under its mount ({@code /raw/installers/setup.bin}), which a single-format repository's
 * URL leaves out, so the query is looked up as typed and under the mount of every format naming no coordinate - each
 * one ordered descent from the first key that could start with it to the first that could not, reading only the pages
 * holding matches. Only a path no format describes to a coordinate is a hit, so a Maven jar is found by its coordinate
 * alone, and each is screened as served; the gate's review subtree is never entered.
 *
 * <p>Bounded as the name lookup is: at most {@code limit} hits and {@link NameLookup#EXAMINED} pointers looked at, then
 * cut short with the path to resume after.
 */
final class PathLookup {

    private PathLookup() {
    }

    /** One page: the disclosed hits and the served path to resume strictly after - empty to start from the first, as a
     *  page of no rows answers when there is a hit for the next, {@code null} when nothing remains. */
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
            // A range wholly before the cursor was answered earlier; the ranges are disjoint prefix blocks, so a cursor
            // past a range's start and outside it lies past all of it.
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

    /** The keys the query could start, in store order: as typed, and under the mount of every installed format naming
     *  no coordinate, leaving out one another covers. */
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

    /** Whether {@code path} could start a served path: no empty segment before its last and no {@code .} or {@code ..};
     *  anything else names no stored file and matches nothing. */
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
