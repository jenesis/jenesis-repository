package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.store.ServableNames;

/**
 * A lookup of published coordinates by the start of their name, answered from the version documents the repository
 * already keeps sorted by ecosystem and coordinate: one listing page of the coordinates starting with the prefix, then
 * each one's versions a page at a time, one document read per version and the withheld screen per hit. Nothing is built
 * or stored.
 *
 * <p>Bounded twice, visibly: at most the caller's {@code limit} hits, and at most {@link #EXAMINED} coordinates and
 * versions looked at, so a prefix whose matches are all held, or a coordinate of a hundred thousand versions, costs the
 * same reads as any other and answers cut short with a cursor resuming exactly where it stopped.
 *
 * <p>The order is the repository's: ecosystem, coordinate as stored, version name. The match is the start of the
 * coordinate as the format keys it (a Maven coordinate starts with its group), as typed or in lower case.
 */
final class NameLookup {

    /** How many coordinates, and how many versions of one, a listing page asks for. */
    static final int STRIDE = 100;

    /** How many coordinates and versions one page may look at before it answers, cut short, with what it found. */
    static final int EXAMINED = 1_000;

    private NameLookup() {
    }

    /** Where a page stopped, which the next starts strictly after: the last version looked at, or - with no version - a
     *  coordinate whose versions were all looked at. */
    record Position(String ecosystem, String coordinate, String version) {
    }

    /** One page: the disclosed hits, and where to resume - {@code null} when nothing remains past it. */
    record Page(List<SearchQuery.Hit> hits, Position next) {
    }

    /** One bounded page of the disclosable published versions whose coordinate starts with {@code prefix}, strictly
     *  after {@code after} ({@code null} from the first). */
    static Page lookup(StoreRepositoryInventory inventory, String prefix, Position after, int limit)
            throws IOException {
        if (limit <= 0) {
            return new Page(List.of(), null);
        }
        Walk walk = new Walk(inventory, limit);
        List<String> runs = runs(prefix);
        for (String ecosystem : inventory.ecosystems()) {
            if (after != null && ecosystem.compareTo(after.ecosystem()) < 0) {
                continue;
            }
            int first = 0;
            String coordinate = null;
            if (after != null && ecosystem.equals(after.ecosystem())) {
                first = Math.max(0, run(runs, after.coordinate()));
                if (run(runs, after.coordinate()) >= 0) {
                    coordinate = after.coordinate();
                    if (after.version() != null && !walk.versions(ecosystem, coordinate, after.version())) {
                        return walk.page();
                    }
                }
            }
            for (int run = first; run < runs.size(); run++) {
                String start = run == first ? coordinate : null;
                while (true) {
                    if (!walk.budget()) {
                        return walk.page();
                    }
                    StoreRepositoryInventory.CoordinatePage page = inventory.coordinates(ecosystem, runs.get(run),
                            start, STRIDE);
                    for (String named : page.coordinates()) {
                        walk.examined++;
                        if (!walk.versions(ecosystem, named, null)) {
                            return walk.page();
                        }
                    }
                    if (page.next() == null) {
                        break;
                    }
                    start = page.next();
                }
            }
        }
        walk.exhausted = true;
        return walk.page();
    }

    /** The prefixes a lookup reads, in store order: the query as typed and, where different, in lower case, since a
     *  format that folds case keys the folded form - a package pushed as {@code Demo} and kept as {@code demo} is found
     *  by the name its publisher gave. They differ only in case, so neither prefixes the other and their runs never
     *  overlap. */
    private static List<String> runs(String prefix) {
        String folded = prefix.toLowerCase(Locale.ROOT);
        return folded.equals(prefix) ? List.of(prefix) : List.copyOf(new TreeSet<>(List.of(prefix, folded)));
    }

    /** Which run {@code coordinate} belongs to, or {@code -1} for none - a cursor from another query. */
    private static int run(List<String> runs, String coordinate) {
        for (int run = 0; run < runs.size(); run++) {
            if (coordinate.startsWith(runs.get(run))) {
                return run;
            }
        }
        return -1;
    }

    /** One page's progress: the hits so far, one past the limit when there is one, and the last version seen. */
    private static final class Walk {

        private final StoreRepositoryInventory inventory;
        private final int limit;
        private final List<SearchQuery.Hit> hits = new ArrayList<>();
        private final List<Position> positions = new ArrayList<>();
        private Position last;
        private int examined;
        private boolean exhausted;

        private Walk(StoreRepositoryInventory inventory, int limit) {
            this.inventory = inventory;
            this.limit = limit;
        }

        private boolean budget() {
            return examined < EXAMINED;
        }

        /** Walk one coordinate's versions after {@code after}; {@code false} once the page is full or the budget
         *  spent. */
        private boolean versions(String ecosystem, String coordinate, String after) throws IOException {
            String from = after;
            while (true) {
                if (!budget() || hits.size() > limit) {
                    return false;
                }
                StoreRepositoryInventory.ReleasePage page = inventory.versions(ecosystem, coordinate, from, STRIDE);
                for (Release release : page.releases()) {
                    examined++;
                    Position position = new Position(ecosystem, coordinate, release.version());
                    last = position;
                    if (inventory.disclosable(ecosystem, coordinate, release.version(),
                            ServableNames.Policy.HIDE_WITHHELD)) {
                        hits.add(SearchQuery.Hit.coordinate(ecosystem, coordinate, release.version()));
                        positions.add(position);
                        if (hits.size() > limit) {
                            return false;
                        }
                    }
                    if (!budget()) {
                        return false;
                    }
                }
                if (page.next() == null) {
                    last = new Position(ecosystem, coordinate, null);
                    return true;
                }
                from = page.next();
            }
        }

        /** The page as found: the first {@code limit} hits, resuming after the last of them when one more was found,
         *  after the last version looked at when the budget ran out, and nowhere when the lookup finished. */
        private Page page() {
            if (hits.size() > limit) {
                return new Page(hits.subList(0, limit), positions.get(limit - 1));
            }
            return new Page(hits, exhausted ? null : last);
        }
    }
}
