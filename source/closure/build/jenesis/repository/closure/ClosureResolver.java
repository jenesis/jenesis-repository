package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * Resolves a version's transitive closure from what the repositories of a {@link ClosureWalk} hold: breadth-first from
 * the version's declared dependencies, each requirement taking the newest held version its ecosystem's
 * {@link RequirementGrammar} admits and the repository holding it serves - the earlier repository of the walk where
 * two hold the same version, as a request walks them - and that version's own declarations read next. The nearest
 * declaration of a coordinate wins, as the build tools that mediate do, and a coordinate is visited once.
 *
 * <p>A held version's declarations are the ones its document records where its publish recorded them, and otherwise
 * the ones an installed inspector reads off its smallest claimed file - a cached copy records none, so its manifest is
 * read. A file past {@link #MANIFEST_LIMIT} is not read: a manifest is small, and an archive that large is not one.
 *
 * <p>Bounded: at most {@link #MAX_COMPONENTS} components, and {@link #MAX_VERSIONS} versions of one coordinate
 * examined; a closure stopped by either says so ({@link ClosureSection.Closure#truncated}). Nothing is fetched.
 */
public final class ClosureResolver {

    /** The most components one closure records before it stops and says so. */
    static final int MAX_COMPONENTS = 2_000;

    /** The most versions of one coordinate examined for the newest a requirement admits. */
    static final int MAX_VERSIONS = 2_000;

    /** The largest file read for its declarations. */
    static final int MANIFEST_LIMIT = 4 * 1024 * 1024;

    private static final int PAGE = 200;

    private final List<Reader> readers;
    private final List<QualityInspector> inspectors;

    /** One repository of the walk, as the resolver reads it. */
    private record Reader(String repository, ArtifactStore store, StoreRepositoryInventory inventory,
                          Publication publication) {

        Reader(ClosureWalk.Member member, boolean own) {
            this(own ? "" : member.repository(), member.store(), new StoreRepositoryInventory(member.store()),
                    new Publication(member.store()));
        }
    }

    public ClosureResolver(ArtifactStore store, List<QualityInspector> inspectors) {
        this(ClosureWalk.of(store), inspectors);
    }

    public ClosureResolver(ClosureWalk walk, List<QualityInspector> inspectors) {
        List<Reader> readers = new ArrayList<>();
        for (ClosureWalk.Member member : walk.members()) {
            readers.add(new Reader(member, readers.isEmpty()));
        }
        this.readers = List.copyOf(readers);
        this.inspectors = List.copyOf(inspectors);
    }

    /** The closure of {@code ecosystem}'s {@code coordinate} at {@code version}, as of {@code now}. */
    public ClosureSection.Closure resolve(String ecosystem, String coordinate, String version, Instant now)
            throws IOException {
        RequirementGrammar grammar = RequirementGrammar.of(ecosystem);
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinate);
        record Pending(ComplianceGate.Dependency dependency, int depth) {
        }
        Optional<List<ComplianceGate.Dependency>> roots = declarations(readers.getFirst(), ecosystem, coordinate,
                version);
        if (roots.isEmpty()) {
            return new ClosureSection.Closure(ClosureSection.Status.UNDECLARED, List.of(), List.of(), false, now,
                    ClosureSource.Kind.DECLARATIONS,
                DeclaredClosure.NAME);
        }
        Deque<Pending> queue = new ArrayDeque<>();
        for (ComplianceGate.Dependency dependency : roots.get()) {
            queue.add(new Pending(dependency, 1));
        }
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Pending next = queue.poll();
            ComplianceGate.Dependency dependency = next.dependency();
            if (!seen.add(dependency.coordinate())) {
                continue;
            }
            if (components.size() >= MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            Choice choice = choose(ecosystem, grammar, dependency);
            if (choice.holding() == null) {
                cuts.add(new ClosureSection.Cut(dependency.coordinate(), dependency.requirement(), choice.reason()));
                truncated |= choice.truncated();
                continue;
            }
            StoreRepositoryInventory.Holding held = choice.holding();
            components.add(new ClosureSection.Component(held.coordinate(), held.version(), held.cached(),
                    next.depth(), choice.reader().repository()));
            for (ComplianceGate.Dependency transitive : declared(choice.reader(), ecosystem, held.coordinate(),
                    held.version())) {
                queue.add(new Pending(transitive, next.depth() + 1));
            }
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now,
                ClosureSource.Kind.DECLARATIONS,
                DeclaredClosure.NAME);
    }

    /** The held version a dependency resolves to and the repository holding it, or why none does. */
    private record Choice(StoreRepositoryInventory.Holding holding, Reader reader, String reason, boolean truncated) {

        static Choice cut(String reason, boolean truncated) {
            return new Choice(null, null, reason, truncated);
        }
    }

    /** A held version a requirement admits, and the repository of the walk holding it. */
    private record Candidate(StoreRepositoryInventory.Holding holding, Reader reader) {
    }

    private Choice choose(String ecosystem, RequirementGrammar grammar, ComplianceGate.Dependency dependency)
            throws IOException {
        List<Candidate> admitted = new ArrayList<>();
        boolean anyHeld = false;
        boolean unknown = false;
        boolean unexamined = false;
        int examined = 0;
        for (Reader reader : readers) {
            String after = null;
            do {
                StoreRepositoryInventory.HoldingPage page = reader.inventory().holdings(ecosystem,
                        dependency.coordinate(), after, PAGE);
                for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                    anyHeld = true;
                    switch (grammar.admits(dependency.requirement(), holding.version())) {
                        case ADMITS -> admitted.add(new Candidate(holding, reader));
                        case UNKNOWN -> unknown = true;
                        case EXCLUDES -> {
                        }
                    }
                }
                examined += page.holdings().size();
                after = page.next();
            } while (after != null && examined < MAX_VERSIONS);
            unexamined |= after != null;
            if (examined >= MAX_VERSIONS) {
                break;
            }
        }
        if (!anyHeld) {
            return Choice.cut(readers.size() == 1 ? "not held by this repository"
                    : "not held by this repository or a repository its fallbacks name", false);
        }
        // Newest first; a stable sort keeps the walk's order among repositories holding the same version.
        admitted.sort((left, right) -> grammar.compare(right.holding().version(), left.holding().version()));
        for (Candidate candidate : admitted) {
            if (candidate.reader().inventory().disclosable(ecosystem, candidate.holding().coordinate(),
                    candidate.holding().version(), ServableNames.Policy.HIDE_WITHHELD)) {
                return new Choice(candidate.holding(), candidate.reader(), null, false);
            }
        }
        if (!admitted.isEmpty()) {
            return Choice.cut("every held version it admits is held for review", false);
        }
        if (unknown) {
            return Choice.cut("the requirement could not be evaluated", false);
        }
        return Choice.cut(unexamined
                ? "no examined version satisfies the requirement, and more are held than are examined"
                : "no held version satisfies the requirement", unexamined);
    }

    /** What a held version declares, empty where it declares nothing readable - see {@link #declarations}. */
    private List<ComplianceGate.Dependency> declared(Reader reader, String ecosystem, String coordinate,
                                                     String version) throws IOException {
        return declarations(reader, ecosystem, coordinate, version).orElse(List.of());
    }

    /** What a held version declares: its document's record where the publish made one, its manifest otherwise, and
     *  empty where neither says anything - no record, and no file an inspector reads a dependency from. */
    private Optional<List<ComplianceGate.Dependency>> declarations(Reader reader, String ecosystem, String coordinate,
                                                                   String version) throws IOException {
        Optional<List<DependencySection.Declared>> recorded = reader.inventory().dependencies(ecosystem, coordinate,
                version);
        if (recorded.isPresent()) {
            return Optional.of(recorded.get().stream()
                    .map(declared -> new ComplianceGate.Dependency(declared.coordinate(), declared.requirement()))
                    .toList());
        }
        for (String path : bySize(reader.inventory().paths(ecosystem, coordinate, version))) {
            List<ComplianceGate.Dependency> read = manifest(reader, path);
            if (!read.isEmpty()) {
                return Optional.of(read);
            }
        }
        return Optional.empty();
    }

    /** The dependencies the inspectors claiming {@code path} read off its stored bytes, empty where none claims it or
     *  it is too large to be a manifest. */
    private List<ComplianceGate.Dependency> manifest(Reader reader, String path) throws IOException {
        List<QualityInspector> claiming = inspectors.stream()
                .filter(inspector -> inspector.claims(path, QualityInspector.Lookup.NONE)).toList();
        if (claiming.isEmpty()) {
            return List.of();
        }
        Optional<Publication.Located> located = reader.publication().locate(path);
        if (located.isEmpty() || located.get().size() > MANIFEST_LIMIT) {
            return List.of();
        }
        byte[] body;
        try (InputStream in = reader.store().open(located.get().key())) {
            body = in.readNBytes(MANIFEST_LIMIT);
        }
        List<ComplianceGate.Dependency> dependencies = new ArrayList<>();
        for (QualityInspector inspector : claiming) {
            try {
                for (ComplianceGate.Subject subject : inspector.inspect(path, body, QualityInspector.Lookup.NONE)) {
                    dependencies.addAll(subject.dependencies());
                }
            } catch (IOException | RuntimeException unreadable) {
                // A manifest an inspector cannot read declares nothing it can report; the closure records the
                // dependency it reached and what it could read past it.
            }
        }
        return List.copyOf(new LinkedHashSet<>(dependencies));
    }

    /** {@code paths} ordered so a manifest, the smallest file of a version by its name, is read first. */
    private static List<String> bySize(List<String> paths) {
        return paths.stream().sorted(Comparator.comparingInt(ClosureResolver::rank).thenComparing(path -> path))
                .toList();
    }

    private static int rank(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".pom") || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".toml")
                || name.endsWith(".nuspec") || name.endsWith(".yaml") || name.endsWith(".yml")) {
            return 0;
        }
        return name.endsWith(".jar") ? 2 : 1;
    }
}
