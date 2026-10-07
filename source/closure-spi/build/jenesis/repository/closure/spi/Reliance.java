package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A version's resolved dependents: which published versions are built against a version a repository holds, as the
 * closures resolved so far reach it - a closure through this repository, or a bill naming the version by coordinate in
 * another ecosystem, built against whichever copy of the tenant's its build installed. Read through the {@linkplain #over installed provider}, so a surface
 * depends on this SPI and never on the pass that keeps the answer.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Absence sentinel.</b> With no provider installed every answer is {@link #NONE}: nothing has dependents, the
 *       epoch never moves, a page is empty.</li>
 *   <li><b>Tenant scoping.</b> A reliance is over one repository of one tenant; a dependent is read only from that
 *       tenant's repositories, and a page leaves out, unread, a dependent in a repository its caller may not read.</li>
 *   <li><b>Read purity.</b> Every answer reads stored state - rows and documents - and nothing else.</li>
 *   <li><b>Staleness.</b> {@link #usedBy} is unconfirmed: a row whose dependent no longer relies on the version counts
 *       until the provider's reconcile removes it, which is what a ranking wants and never what a page may show;
 *       {@link #dependents} confirms every row against its dependent's own closure.</li>
 *   <li><b>Bounded work.</b> {@link #usedBy} lists at most {@link #USED_BY_CAP} rows, and answers the cap where more
 *       stand; a page reads at most {@link #MAX_PAGE} rows and a document a row, and says where to resume.</li>
 * </ol>
 */
public interface Reliance {

    /** The most rows one page reads. */
    int MAX_PAGE = 200;

    /** The most dependents {@link #usedBy} counts: it answers this many where at least this many stand. */
    int USED_BY_CAP = 100;

    /** Nothing is relied on: no provider is installed. */
    Reliance NONE = new Reliance() {
        @Override
        public int usedBy(String ecosystem, String coordinate, String version) {
            return 0;
        }

        @Override
        public String epoch() {
            return "";
        }

        @Override
        public Page dependents(String ecosystem, String coordinate, String version, String after, int limit,
                               Predicate<String> readable) {
            return new Page(List.of(), 0, Optional.empty());
        }
    };

    /** How many published versions are built against {@code version} of {@code coordinate} of {@code ecosystem}, as
     *  the index's rows say, unconfirmed: {@link #USED_BY_CAP} where at least that many are, {@code 0} where none
     *  is. */
    int usedBy(String ecosystem, String coordinate, String version) throws IOException;

    /** A token every change to what {@link #usedBy} answers moves; {@code ""} where nothing ever changed it - what a
     *  view ordering versions by what relies on them folds into its freshness stamp. */
    String epoch() throws IOException;

    /** One page of the published versions relying on {@code version} of {@code coordinate} of {@code ecosystem}, after
     *  {@code after}, at most {@code limit} rows ({@link #MAX_PAGE} at most), each confirmed by its own closure; a
     *  dependent in a repository {@code readable} refuses is left out. */
    Page dependents(String ecosystem, String coordinate, String version, String after, int limit,
                    Predicate<String> readable) throws IOException;

    /**
     * A published version relying on the version asked about: its repository, ecosystem, coordinate and version, how
     * its closure reaches it - from the dependency it names itself down to the version, both included - whether the
     * closure stopped there, a version held for review being a cut rather than a component, and whether it names the
     * version by coordinate alone, in another ecosystem than its own, and so relies on whichever copy its build
     * installed.
     */
    record Dependent(String repository, String ecosystem, String coordinate, String version,
                     List<ClosureSection.Hop> path, boolean cut, boolean byCoordinate) {

        public Dependent {
            path = List.copyOf(path);
        }
    }

    /** One page of dependents: those the rows of the page named and their closures confirm, how many rows were read,
     *  and the cursor resuming after the last of them, empty once the rows are exhausted. */
    record Page(List<Dependent> dependents, int examined, Optional<String> next) {

        public Page {
            dependents = List.copyOf(dependents);
        }
    }

    /**
     * The installed provider's reliance over {@code holder}, the store of the repository named {@code holderName}, its
     * tenant's other repositories by name through {@code repositories} and the tenant's store {@code tenant}, where
     * the caller reaches it; {@link #NONE} where no provider is installed.
     */
    static Reliance over(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                         Function<String, Optional<ArtifactStore>> repositories) {
        return Providers.singleton("reliance", InstalledReliance.DISCOVERED)
                .map(provider -> provider.over(holder, holderName, tenant, repositories)).orElse(NONE);
    }
}
