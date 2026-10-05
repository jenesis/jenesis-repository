package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.PackageUrls;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * A release's closure as the build that made it resolved it: the bill of materials the version carries - published as
 * a file of its own (a {@code -cyclonedx.json}, an {@code .spdx.json}) or embedded in its archive - taken as written,
 * where it names more than the version's direct dependencies. Each component is the holding the walk's repositories
 * keep of it, a release or a cached copy; one they do not hold, hold for review, or name in another ecosystem is a cut
 * saying so. A bill that names the direct dependencies alone - or none - is not a closure, and the resolvers answer
 * instead.
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
        List<Holder> holders = walk.members().stream().map(Holder::new).toList();
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinate);
        boolean truncated = false;
        for (DependencyComponent component : graph.dependencies()) {
            Optional<PackageUrls.Named> named = named(component, ecosystem);
            if (named.isEmpty()) {
                cuts.add(new ClosureSection.Cut(component.coordinate(), versionOf(component),
                        "named by the version's bill in a form this repository cannot place"));
                continue;
            }
            PackageUrls.Named at = named.get();
            if (!seen.add(at.coordinate())) {
                continue;
            }
            if (!ecosystem.equals(at.ecosystem())) {
                cuts.add(new ClosureSection.Cut(at.coordinate(), at.version(),
                        "named by the version's bill in " + at.ecosystem() + ", another ecosystem"));
                continue;
            }
            if (components.size() >= ClosureResolver.MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            Optional<Held> held = held(holders, ecosystem, at);
            if (held.isEmpty()) {
                cuts.add(new ClosureSection.Cut(at.coordinate(), at.version(), holders.size() == 1
                        ? "named by the version's bill, not held by this repository"
                        : "named by the version's bill, not held by this repository or a repository its fallbacks "
                                + "name"));
            } else if (!held.get().served()) {
                cuts.add(new ClosureSection.Cut(at.coordinate(), at.version(), "held for review"));
            } else {
                components.add(new ClosureSection.Component(at.coordinate(), at.version(), held.get().cached(),
                        depths.getOrDefault(component.ref(), 1), held.get().repository()));
            }
        }
        return Optional.of(new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now, Kind.BILL, NAME));
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

    /** One repository of the walk, as a bill's components are looked up in it. */
    private record Holder(String repository, ArtifactStore store, StoreRepositoryInventory inventory) {

        Holder(ClosureWalk.Member member) {
            this(member.repository(), member.store(), new StoreRepositoryInventory(member.store()));
        }

        /** Whether this repository holds {@code version} of {@code coordinate} for review as no holding yet: a
         *  proxied copy the screen held at its fill, found through its hold's subject and live review pointer. */
        boolean heldAtFill(String ecosystem, String coordinate, String version) throws IOException {
            return HeldVersions.held(store, ecosystem, coordinate, version);
        }
    }

    /** Where a component is held: the repository, as a release or a cached copy, and whether it is served. */
    private record Held(String repository, boolean cached, boolean served) {
    }

    /** The first repository of the walk holding {@code at}, as a release or a cached copy. */
    private static Optional<Held> held(List<Holder> holders, String ecosystem, PackageUrls.Named at)
            throws IOException {
        for (int i = 0; i < holders.size(); i++) {
            Holder holder = holders.get(i);
            boolean released = holder.inventory().publishedAt(ecosystem, at.coordinate(), at.version()).isPresent();
            boolean cached = !released
                    && holder.inventory().cachedAt(ecosystem, at.coordinate(), at.version()).isPresent();
            if (released || cached) {
                return Optional.of(new Held(i == 0 ? "" : holder.repository(), cached,
                        holder.inventory().disclosable(ecosystem, at.coordinate(), at.version(),
                                ServableNames.Policy.HIDE_WITHHELD)));
            }
        }
        for (int i = 0; i < holders.size(); i++) {
            if (holders.get(i).heldAtFill(ecosystem, at.coordinate(), at.version())) {
                return Optional.of(new Held(i == 0 ? "" : holders.get(i).repository(), true, false));
            }
        }
        return Optional.empty();
    }
}
