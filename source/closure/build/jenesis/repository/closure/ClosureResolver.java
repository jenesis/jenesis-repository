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
 * Resolves a version's transitive closure from what one repository holds: breadth-first from the version's declared
 * dependencies, each requirement taking the newest held version its ecosystem's {@link RequirementGrammar} admits and
 * the repository serves, and that version's own declarations read next. The nearest declaration of a coordinate wins,
 * as the build tools that mediate do, and a coordinate is visited once.
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

    private final ArtifactStore store;
    private final StoreRepositoryInventory inventory;
    private final Publication publication;
    private final List<QualityInspector> inspectors;

    public ClosureResolver(ArtifactStore store, List<QualityInspector> inspectors) {
        this.store = Objects.requireNonNull(store, "store");
        this.inventory = new StoreRepositoryInventory(store);
        this.publication = new Publication(store);
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
        Optional<List<ComplianceGate.Dependency>> roots = declarations(ecosystem, coordinate, version);
        if (roots.isEmpty()) {
            return new ClosureSection.Closure(ClosureSection.Status.UNDECLARED, List.of(), List.of(), false, now);
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
                    next.depth()));
            for (ComplianceGate.Dependency transitive : declared(ecosystem, held.coordinate(), held.version())) {
                queue.add(new Pending(transitive, next.depth() + 1));
            }
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now);
    }

    /** The held version a dependency resolves to, or why none does. */
    private record Choice(StoreRepositoryInventory.Holding holding, String reason, boolean truncated) {
    }

    private Choice choose(String ecosystem, RequirementGrammar grammar, ComplianceGate.Dependency dependency)
            throws IOException {
        List<StoreRepositoryInventory.Holding> admitted = new ArrayList<>();
        boolean anyHeld = false;
        boolean unknown = false;
        int examined = 0;
        String after = null;
        do {
            StoreRepositoryInventory.HoldingPage page = inventory.holdings(ecosystem, dependency.coordinate(), after,
                    PAGE);
            for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                anyHeld = true;
                switch (grammar.admits(dependency.requirement(), holding.version())) {
                    case ADMITS -> admitted.add(holding);
                    case UNKNOWN -> unknown = true;
                    case EXCLUDES -> {
                    }
                }
            }
            examined += page.holdings().size();
            after = page.next();
        } while (after != null && examined < MAX_VERSIONS);
        if (!anyHeld) {
            return new Choice(null, "not held by this repository", false);
        }
        admitted.sort((left, right) -> grammar.compare(right.version(), left.version()));
        for (StoreRepositoryInventory.Holding candidate : admitted) {
            if (inventory.disclosable(ecosystem, candidate.coordinate(), candidate.version(),
                    ServableNames.Policy.HIDE_WITHHELD)) {
                return new Choice(candidate, null, false);
            }
        }
        if (!admitted.isEmpty()) {
            return new Choice(null, "every held version it admits is held for review", false);
        }
        if (unknown) {
            return new Choice(null, "the requirement could not be evaluated", false);
        }
        return new Choice(null, after == null ? "no held version satisfies the requirement"
                : "no examined version satisfies the requirement, and more are held than are examined",
                after != null);
    }

    /** What a held version declares, empty where it declares nothing readable - see {@link #declarations}. */
    private List<ComplianceGate.Dependency> declared(String ecosystem, String coordinate, String version)
            throws IOException {
        return declarations(ecosystem, coordinate, version).orElse(List.of());
    }

    /** What a held version declares: its document's record where the publish made one, its manifest otherwise, and
     *  empty where neither says anything - no record, and no file an inspector reads a dependency from. */
    private Optional<List<ComplianceGate.Dependency>> declarations(String ecosystem, String coordinate,
                                                                   String version) throws IOException {
        Optional<List<DependencySection.Declared>> recorded = inventory.dependencies(ecosystem, coordinate, version);
        if (recorded.isPresent()) {
            return Optional.of(recorded.get().stream()
                    .map(declared -> new ComplianceGate.Dependency(declared.coordinate(), declared.requirement()))
                    .toList());
        }
        for (String path : bySize(inventory.paths(ecosystem, coordinate, version))) {
            List<ComplianceGate.Dependency> read = manifest(path);
            if (!read.isEmpty()) {
                return Optional.of(read);
            }
        }
        return Optional.empty();
    }

    /** The dependencies the inspectors claiming {@code path} read off its stored bytes, empty where none claims it or
     *  it is too large to be a manifest. */
    private List<ComplianceGate.Dependency> manifest(String path) throws IOException {
        List<QualityInspector> claiming = inspectors.stream()
                .filter(inspector -> inspector.claims(path, QualityInspector.Lookup.NONE)).toList();
        if (claiming.isEmpty()) {
            return List.of();
        }
        Optional<Publication.Located> located = publication.locate(path);
        if (located.isEmpty() || located.get().size() > MANIFEST_LIMIT) {
            return List.of();
        }
        byte[] body;
        try (InputStream in = store.open(located.get().key())) {
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
