package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.PackageUrls;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.Publication;

/**
 * A release's closure as the build that made it resolved it: the bill of materials the version carries - published as
 * a file of its own (a {@code -cyclonedx.json}, an {@code .spdx.json}) or embedded in its archive - taken as written,
 * where it names more than the version's direct dependencies, each component placed in the walk's repositories as
 * {@link CarriedClosure} places what a release carries. A bill that names the direct dependencies alone - or none -
 * is not a closure, and the resolvers answer instead.
 *
 * <p>A component's depth is its distance from the root along the bill's dependency edges, and {@code 1} where the bill
 * records none - a flat component list is the resolved closure CycloneDX records. Bounded at
 * {@link ClosureResolver#MAX_COMPONENTS} components, a closure stopped there saying so; an archive is read only as far
 * as its bill, within the dependency module's own budget. Nothing is fetched.
 */
public final class CarriedBill implements ClosureSource {

    /** The source's name. */
    public static final String NAME = "carried-bill";

    @Override
    public String name() {
        return NAME;
    }

    /** Every ecosystem: a bill published as a file of a version is read whatever its format, and an archive's
     *  embedded one wherever the version's files are archives. */
    @Override
    public Set<String> ecosystems() {
        return Ecosystems.canonical();
    }

    @Override
    public Kind kind() {
        return Kind.BILL;
    }

    /** The closure the bill {@code coordinate} at {@code version} carries names, or empty where it carries none naming
     *  more than its direct dependencies. The release is the first repository of {@code walk}'s. */
    @Override
    public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                    String version, Instant now) throws IOException {
        ClosureWalk.Member own = walk.members().getFirst();
        Optional<DependencyGraph> bill = bill(own, ecosystem, coordinate, version);
        if (bill.isEmpty()) {
            return Optional.empty();
        }
        DependencyGraph graph = bill.get();
        Map<String, Integer> depths = depths(graph);
        List<CarriedClosure.Entry> entries = new ArrayList<>();
        for (DependencyComponent component : graph.dependencies()) {
            entries.add(named(component, ecosystem)
                    .map(at -> CarriedClosure.Entry.placed(at.ecosystem(), at.coordinate(), at.version(),
                            depths.getOrDefault(component.ref(), 1)))
                    .orElseGet(() -> CarriedClosure.Entry.unplaced(component.coordinate(), versionOf(component),
                            "named by the version's bill in a form this repository cannot place")));
        }
        return Optional.of(CarriedClosure.place(walk, ecosystem, coordinate, version, entries, "the version's bill",
                NAME, now));
    }

    /** The first bill among the version's files that names a closure: a published bill before an embedding archive. */
    private static Optional<DependencyGraph> bill(ClosureWalk.Member own, String ecosystem, String coordinate,
                                                  String version) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(own.store());
        Publication publication = new Publication(own.store());
        List<String> candidates = inventory.paths(ecosystem, coordinate, version).stream()
                .filter(path -> ArtifactSbom.isDocument(path) || archive(path))
                .sorted(Comparator.comparing((String path) -> !ArtifactSbom.isDocument(path)))
                .toList();
        for (String path : candidates) {
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                continue;
            }
            Optional<DependencyGraph> read;
            try (InputStream in = own.store().open(key.get())) {
                read = ArtifactSbom.isDocument(path) ? ArtifactSbom.document(in) : ArtifactSbom.graph(in);
            }
            if (read.isPresent() && namesClosure(read.get())) {
                return read;
            }
        }
        return Optional.empty();
    }

    /** Whether {@code graph} names more than its root's direct dependencies: a component past the root's own edges,
     *  or components with no edges at all, which is how a flat bill records the resolved closure. */
    static boolean namesClosure(DependencyGraph graph) {
        List<DependencyComponent> dependencies = graph.dependencies();
        if (dependencies.isEmpty()) {
            return false;
        }
        if (graph.edges().isEmpty()) {
            return true;
        }
        return dependencies.size() > graph.directDependencies().size();
    }

    private static boolean archive(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".ear");
    }

    /** Each component's distance from the root along the bill's edges. */
    private static Map<String, Integer> depths(DependencyGraph graph) {
        Map<String, Integer> depths = new HashMap<>();
        if (graph.rootRef() == null) {
            return depths;
        }
        Map<String, List<String>> edges = new HashMap<>();
        for (DependencyEdge edge : graph.edges()) {
            edges.computeIfAbsent(edge.from(), _ -> new ArrayList<>()).add(edge.to());
        }
        Deque<String> queue = new ArrayDeque<>(List.of(graph.rootRef()));
        depths.put(graph.rootRef(), 0);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (String next : edges.getOrDefault(at, List.of())) {
                if (!depths.containsKey(next)) {
                    depths.put(next, depths.get(at) + 1);
                    queue.add(next);
                }
            }
        }
        return depths;
    }

    /** The coordinate a component names: its package URL's where it carries one, its group and name in the
     *  version's own ecosystem otherwise. */
    private static Optional<PackageUrls.Named> named(DependencyComponent component, String ecosystem) {
        if (component.purl() != null && !component.purl().isBlank()) {
            return PackageUrls.parse(component.purl().strip());
        }
        if (component.name() == null || component.name().isBlank() || component.version() == null
                || component.version().isBlank()) {
            return Optional.empty();
        }
        boolean grouped = component.group() != null && !component.group().isBlank();
        String coordinate = grouped ? component.group().strip() + (Ecosystems.MAVEN.equals(ecosystem) ? ":" : "/")
                + component.name().strip() : component.name().strip();
        return Optional.of(new PackageUrls.Named(ecosystem, coordinate, component.version().strip()));
    }

    private static String versionOf(DependencyComponent component) {
        return component.version() == null ? "" : component.version();
    }
}
