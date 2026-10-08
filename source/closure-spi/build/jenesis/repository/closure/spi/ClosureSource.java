package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * One way a release's bill of materials - its transitive closure - is produced, discovered and named, serving the
 * ecosystems it declares. A source is one of two roles. A {@link Carried} source reads a document the release carries
 * - its lock file, its bill - as the packages it names, and the closure pass places each one in the repositories of
 * the walk: what a carried document says is the source's, where it stands is the pass's, so two documents naming one
 * package place it alike. A {@link Resolving} source works the closure out from what the walk holds - an ecosystem's
 * own resolver (Maven Resolver for Maven), or the walk over each held version's declarations - and so places what it
 * chooses itself. The pass asks the sources serving a release's ecosystem in {@link Kind} order - a carried lock file,
 * a carried bill, a resolver, then the declaration walk - and, within a kind, by name; the first that answers is the
 * closure, recorded under that source's own kind and name by the pass, and the rest are not asked.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Shared and stateless: the closure pass may ask one instance about several releases at
 *       once.</li>
 *   <li><b>Absence sentinel.</b> {@link Optional#empty()} where it has nothing to say about this release - no
 *       document carried, no descriptor its resolver reads - so the next source in order answers; a resolution it
 *       began is a closure, partial where a subtree failed, never an exception for what the store does not hold.</li>
 *   <li><b>Read purity.</b> A carried source reads only the release's own files, in the repository that holds it; a
 *       resolving source reads only the stores of the walk it is handed - the release's repository, then the
 *       repositories its fallbacks name. Neither makes a network request; what none holds is a cut.</li>
 *   <li><b>Bounded work.</b> A closure records at most {@link #MAX_COMPONENTS} components and says so in
 *       {@link ClosureSection.Closure#truncated}; a carried source's entries are bounded by the document it reads,
 *       which is read within the shared archive and inflation bounds; an archive is read only as far as it must
 *       be.</li>
 *   <li><b>Error visibility.</b> A store read that fails raises, and the pass leaves the release unresolved for the
 *       next pass rather than recording a closure a failed read shortened.</li>
 *   <li><b>Selection.</b> {@code ALL}, ordered by {@link #kind()} then {@link #name()}; a name taken twice, or a kind
 *       its role cannot have ({@link Kind#carried()}), is a packaging error the discovery raises. A source serves
 *       exactly the ecosystems {@link #ecosystems()} names, in the canonical spelling a version's document uses, and
 *       is never asked about another.</li>
 *   <li><b>Lifecycle.</b> Discovered once for the JVM's life ({@link #installed()}): the installed set is fixed by the
 *       module graph, and the pass asks it once per release.</li>
 * </ol>
 */
public sealed interface ClosureSource permits ClosureSource.Carried, ClosureSource.Resolving {

    /** Where a source stands in the order the pass asks in. */
    enum Kind {
        /** A lock file the release carries, as the package manager that resolved it wrote it: exact, and of the
         *  release's own ecosystem, so it is asked before a bill, which a build generated afterwards. */
        LOCK(true),
        /** The bill the release carries, as its build resolved it. */
        BILL(true),
        /** The ecosystem's own resolver over the descriptors the walk holds. */
        RESOLVER(false),
        /** The walk over each held version's declarations, for an ecosystem nothing earlier resolves. */
        DECLARATIONS(false);

        private final boolean carried;

        Kind(boolean carried) {
            this.carried = carried;
        }

        /** Whether a source of this kind is {@link Carried} rather than {@link Resolving}. */
        public boolean carried() {
            return carried;
        }
    }

    /** The source's name: its attribution in the closure and its identity among the installed sources. */
    String name();

    /** The ecosystems it serves, in the canonical spelling a version's document uses. */
    Set<String> ecosystems();

    /** Where it stands in the order the pass asks in. */
    Kind kind();

    /** A source that reads a document the release carries as the packages it names, which the pass places. */
    non-sealed interface Carried extends ClosureSource {

        /** What {@code coordinate} at {@code version} of {@code ecosystem} carries, read from its files in
         *  {@code release}, the store of the repository holding it; empty where it carries no document this source
         *  reads, or one naming nothing past the release's direct dependencies. */
        Optional<Carriage> read(ArtifactStore release, String ecosystem, String coordinate, String version)
                throws IOException;
    }

    /** A source that works a release's closure out from what the repositories of a walk hold. */
    non-sealed interface Resolving extends ClosureSource {

        /** {@code coordinate} at {@code version}'s closure, of {@code ecosystem}, as the repositories of {@code walk}
         *  hold it, as of {@code now}; empty where this source has nothing to say about the release. */
        Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                 String version, Instant now) throws IOException;
    }

    /** What a release carries, read: the document it was read from, as a closure's cuts name it ("the version's
     *  bill", "the version's Cargo.lock"), and the packages it names, in the order it names them. */
    record Carriage(String document, List<Entry> entries) {

        public Carriage {
            Objects.requireNonNull(document, "document");
            entries = List.copyOf(entries);
        }
    }

    /**
     * One package a carried document names: where it can be placed, its ecosystem, coordinate and version at
     * {@code depth} from the root, and the package {@code viaCoordinate} at {@code viaVersion} whose dependencies named
     * it - none where the root named it itself; where it cannot, {@code unplaced} says why and the rest is what the
     * document wrote. {@code purl} is the package URL the document named it by, empty where it named none, kept so
     * what the coordinate leaves out of it - a distribution package's release and architecture - is not lost.
     */
    record Entry(String ecosystem, String coordinate, String version, int depth, String viaCoordinate,
                 String viaVersion, String unplaced, String purl) {

        public Entry {
            purl = purl == null ? "" : purl;
        }

        /** A package the root names itself, placed at {@code depth}. */
        public static Entry placed(String ecosystem, String coordinate, String version, int depth) {
            return new Entry(ecosystem, coordinate, version, depth, "", "", null, "");
        }

        /** A package placed at {@code depth}, named by the dependencies of {@code viaCoordinate} at
         *  {@code viaVersion}. */
        public static Entry placed(String ecosystem, String coordinate, String version, int depth,
                                   String viaCoordinate, String viaVersion) {
            return new Entry(ecosystem, coordinate, version, depth, viaCoordinate, viaVersion, null, "");
        }

        /** A package named in a form no repository can place, for {@code reason}. */
        public static Entry unplaced(String coordinate, String version, String reason) {
            return new Entry(null, coordinate, version == null ? "" : version, 0, "", "", reason, "");
        }

        /** This entry as the package URL {@code purl} named it, kept beside the coordinate it was read as. */
        public Entry named(String purl) {
            return new Entry(ecosystem, coordinate, version, depth, viaCoordinate, viaVersion, unplaced, purl);
        }
    }

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
