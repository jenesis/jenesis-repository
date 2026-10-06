package build.jenesis.repository.closure.spi;

import module java.base;

/**
 * One way a release's bill of materials - its transitive closure - is produced, discovered and named, serving the
 * ecosystems it declares: the lock file the release carries, the bill it carries, an ecosystem's own resolver (Maven
 * Resolver for Maven), a scanner that reads what an artifact contains, or the walk over each held version's
 * declarations. The closure pass asks the sources serving a release's ecosystem in {@link Kind} order - a carried lock
 * file, a carried bill, a resolver, a scanner, then the declaration walk - and, within a kind, by name; the first that
 * answers is the closure, recorded under that source's own kind and name, and the rest are not asked.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Shared and stateless: the closure pass may ask one instance about several releases at
 *       once.</li>
 *   <li><b>Absence sentinel.</b> {@link Optional#empty()} where it has nothing to say about this release - no bill
 *       carried, no descriptor its resolver reads - so the next source in order answers; a resolution it began is a
 *       closure, partial where a subtree failed, never an exception for what the store does not hold.</li>
 *   <li><b>Read purity.</b> It reads only the stores of the walk it is handed - the release's repository, then the
 *       repositories its fallbacks name - and makes no network request; what none holds is a cut. A scanner that runs
 *       beside the deployment is reached through the deployment's own configuration, never a feed's.</li>
 *   <li><b>Bounded work.</b> It stops at {@link #MAX_COMPONENTS} components and says so in
 *       {@link ClosureSection.Closure#truncated}; an archive is read only as far as it must be.</li>
 *   <li><b>Error visibility.</b> A store read that fails raises, and the pass leaves the release unresolved for the
 *       next pass rather than recording a closure a failed read shortened.</li>
 *   <li><b>Selection.</b> {@code ALL}, ordered by {@link #kind()} then {@link #name()}; a name taken twice is a
 *       packaging error the discovery raises. A source serves exactly the ecosystems {@link #ecosystems()} names, in
 *       the canonical spelling a version's document uses, and is never asked about another.</li>
 *   <li><b>Lifecycle.</b> Discovered once for the JVM's life ({@link #installed()}): the installed set is fixed by the
 *       module graph, and the pass asks it once per release.</li>
 * </ol>
 */
public interface ClosureSource {

    /** Where a source stands in the order the pass asks in. */
    enum Kind {
        /** A lock file the release carries, as the package manager that resolved it wrote it: exact, and of the
         *  release's own ecosystem, so it is asked before a bill, which a build generated afterwards. */
        LOCK,
        /** The bill the release carries, as its build resolved it. */
        BILL,
        /** The ecosystem's own resolver over the descriptors the walk holds. */
        RESOLVER,
        /** A scanner that reads what the release's artifacts contain. */
        SCANNER,
        /** The walk over each held version's declarations, for an ecosystem nothing earlier resolves. */
        DECLARATIONS
    }

    /** The source's name: its attribution in the closure and its identity among the installed sources. */
    String name();

    /** The ecosystems it serves, in the canonical spelling a version's document uses. */
    Set<String> ecosystems();

    /** Where it stands in the order the pass asks in. */
    Kind kind();

    /** {@code coordinate} at {@code version}'s closure, of {@code ecosystem}, as the repositories of {@code walk} hold
     *  it, as of {@code now}; empty where this source has nothing to say about the release. */
    Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate, String version,
                                             Instant now) throws IOException;

    /** The most components one closure records, whichever source produced it: one past it, the closure stops and
     *  says so. */
    int MAX_COMPONENTS = 2_000;

    /** Every installed source, in the order the pass asks them. */
    static List<ClosureSource> installed() {
        return InstalledClosures.SOURCES;
    }

    /** The installed sources serving {@code ecosystem}, in the order the pass asks them. */
    static List<ClosureSource> serving(String ecosystem) {
        return installed().stream().filter(source -> source.ecosystems().contains(ecosystem)).toList();
    }
}
