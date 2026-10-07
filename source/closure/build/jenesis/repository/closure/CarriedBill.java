package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.VersionBills;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.PackageUrls;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * A release's closure as the build that made it resolved it: the bill of materials the version carries - published as
 * a file of its own (a {@code -cyclonedx.json}, an {@code .spdx.json}), embedded in its archive, or attached to it by
 * a scanner of its content ({@link VersionBills}) - taken as written,
 * where it names more than the version's direct dependencies, each component placed in the walk's repositories as
 * the pass places what a release carries. A bill that names the direct dependencies alone - or none -
 * is not a closure, and the resolvers answer instead.
 *
 * <p>A component's depth is its distance from the root along the bill's dependency edges, and {@code 1} where the bill
 * records none - a flat component list is the resolved closure CycloneDX records. Bounded at
 * {@link ClosureSource#MAX_COMPONENTS} components, a closure stopped there saying so; an archive is read only as far
 * as its bill, within the dependency module's own budget. Nothing is fetched.
 */
public final class CarriedBill implements ClosureSource.Carried {

    /** The source's name. */
    public static final String NAME = "carried-bill";

    @Override
    public String name() {
        return NAME;
    }

    /** Every ecosystem an installed format declares: a bill published as a file of a version, or attached to it, is
     *  read whatever its format, and an archive's embedded one wherever the version's files are archives. */
    @Override
    public Set<String> ecosystems() {
        return StoreRepositoryInventory.installedEcosystems();
    }

    @Override
    public Kind kind() {
        return Kind.BILL;
    }

    /** The packages the bill {@code coordinate} at {@code version} carries names, or empty where it carries none
     *  naming more than its direct dependencies. */
    @Override
    public Optional<ClosureSource.Carriage> read(ArtifactStore release, String ecosystem, String coordinate,
                                                 String version) throws IOException {
        Optional<DependencyGraph> bill = bill(release, ecosystem, coordinate, version);
        if (bill.isEmpty()) {
            return Optional.empty();
        }
        DependencyGraph graph = bill.get();
        Tree tree = Tree.of(graph);
        Map<String, PackageUrls.Named> names = new HashMap<>();
        for (DependencyComponent component : graph.dependencies()) {
            named(component, ecosystem).ifPresent(at -> names.put(component.ref(), at));
        }
        List<ClosureSource.Entry> entries = new ArrayList<>();
        for (DependencyComponent component : graph.dependencies()) {
            // The component whose dependencies name this one, where the bill's graph says so and it is not the root.
            PackageUrls.Named via = names.get(tree.parents().get(component.ref()));
            entries.add(named(component, ecosystem)
                    .map(at -> via == null
                            ? ClosureSource.Entry.placed(at.ecosystem(), at.coordinate(), at.version(),
                                    tree.depths().getOrDefault(component.ref(), 1))
                            : ClosureSource.Entry.placed(at.ecosystem(), at.coordinate(), at.version(),
                                    tree.depths().getOrDefault(component.ref(), 1), via.coordinate(), via.version()))
                    .orElseGet(() -> ClosureSource.Entry.unplaced(component.coordinate(), versionOf(component),
                            "named by the version's bill in a form this repository cannot place")));
        }
        return Optional.of(new ClosureSource.Carriage("the version's bill", entries));
    }

    /** The first bill that names a closure among the version's files - a published bill before an embedding archive -
     *  and then the bill attached to it ({@link VersionBills}). */
    private static Optional<DependencyGraph> bill(ArtifactStore release, String ecosystem, String coordinate,
                                                  String version) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(release);
        Publication publication = new Publication(release);
        List<String> candidates = billOrder(inventory.paths(ecosystem, coordinate, version)).stream()
                .filter(path -> ArtifactSbom.isDocument(path) || archive(path))
                .toList();
        for (String path : candidates) {
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                continue;
            }
            Optional<DependencyGraph> read;
            try (InputStream in = release.open(key.get())) {
                read = read(path, in);
            }
            if (read.isPresent() && namesClosure(read.get())) {
                return read;
            }
        }
        Optional<InputStream> attached = VersionBills.open(release, ecosystem, coordinate, version);
        if (attached.isPresent()) {
            Optional<DependencyGraph> read;
            try (InputStream in = attached.get()) {
                read = ArtifactSbom.document(in);
            }
            if (read.isPresent() && namesClosure(read.get())) {
                return read;
            }
        }
        return Optional.empty();
    }

    /**
     * {@code paths}, a version's files, in the order the bill it carries is read from them - what the closure and the
     * bill of materials the API and the console serve for a version both read: a bill published as a file of its own
     * (a {@code -cyclonedx.json}, an {@code .spdx.json}) first, then an archive, which may embed one, then a
     * descriptor ({@code .pom}, {@code .xml}, {@code .json}), through which only what its manifest declared answers.
     * Stable within each rank.
     */
    public static List<String> billOrder(Collection<String> paths) {
        return paths.stream().sorted(Comparator.comparingInt(path -> ArtifactSbom.isDocument(path) ? 0
                : path.endsWith(".pom") || path.endsWith(".xml") || path.endsWith(".json") ? 2 : 1)).toList();
    }

    /** The bill the file at {@code path} carries, read from its bytes {@code in}: the document itself where it is a
     *  bill published as a file, the one an archive embeds otherwise; empty where it carries none. */
    public static Optional<DependencyGraph> read(String path, InputStream in) throws IOException {
        return ArtifactSbom.isDocument(path) ? ArtifactSbom.document(in) : ArtifactSbom.graph(in);
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

    /** A bill's graph walked from its root: each component's distance from the root, and the component whose edge
     *  reached it first; empty where the bill names no root. */
    private record Tree(Map<String, Integer> depths, Map<String, String> parents) {

        static Tree of(DependencyGraph graph) {
            if (graph.rootRef() == null) {
                return new Tree(Map.of(), Map.of());
            }
            Map<String, List<String>> edges = new HashMap<>();
            for (DependencyEdge edge : graph.edges()) {
                edges.computeIfAbsent(edge.from(), _ -> new ArrayList<>()).add(edge.to());
            }
            Map<String, Integer> depths = new HashMap<>();
            Map<String, String> parents = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>(List.of(graph.rootRef()));
            depths.put(graph.rootRef(), 0);
            while (!queue.isEmpty()) {
                String at = queue.poll();
                for (String next : edges.getOrDefault(at, List.of())) {
                    if (!depths.containsKey(next)) {
                        depths.put(next, depths.get(at) + 1);
                        parents.put(next, at);
                        queue.add(next);
                    }
                }
            }
            return new Tree(Collections.unmodifiableMap(depths), Collections.unmodifiableMap(parents));
        }
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
