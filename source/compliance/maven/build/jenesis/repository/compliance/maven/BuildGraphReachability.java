package build.jenesis.repository.compliance.maven;

import module java.base;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDependencyKey;
import build.jenesis.maven.MavenDependencyValue;
import build.jenesis.maven.MavenResolver;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;

/**
 * Build-graph reachability over a resolved Maven closure: for every resolved dependency, the shortest path from the
 * artifact to it, so a finding about a transitive dependency says whether, and how directly, the component is reachable
 * on the build graph. It reads the closure the gate already resolves, costing no extra resolution.
 *
 * <p>The graph comes from {@link MavenResolver.Closure#edges()}: a {@code null} parent marks a direct dependency (depth
 * one), every other edge is {@code parent -> child}. A closure resolves each coordinate to one version, so nodes match
 * on {@link MavenDependencyKey} and the resolved version is read back for the path. A breadth-first walk from the
 * direct dependencies gives each node's shortest depth and path; one the walk never reaches stays
 * {@link ComplianceGate.Reachability#UNKNOWN}.
 */
public final class BuildGraphReachability {

    private BuildGraphReachability() {
    }

    /** Maps every resolved dependency in the closure to where it sits on the build graph. */
    public static Map<MavenDependencyKey, ComplianceGate.Reachability> of(MavenResolver.Closure closure, String prefix) {
        Set<MavenDependencyKey> directs = new LinkedHashSet<>();
        Map<MavenDependencyKey, List<MavenDependencyKey>> adjacency = new LinkedHashMap<>();
        for (Resolver.Edge edge : closure.edges()) {
            MavenDependencyKey child = keyOf(edge.coordinate(), prefix);
            if (child == null) {
                continue;
            }
            if (edge.parent() == null) {
                directs.add(child);
            } else {
                MavenDependencyKey parent = keyOf(edge.parent(), prefix);
                if (parent != null) {
                    adjacency.computeIfAbsent(parent, _ -> new ArrayList<>()).add(child);
                }
            }
        }
        Map<MavenDependencyKey, Integer> depth = new LinkedHashMap<>();
        Map<MavenDependencyKey, MavenDependencyKey> predecessor = new HashMap<>();
        Deque<MavenDependencyKey> queue = new ArrayDeque<>();
        for (MavenDependencyKey direct : directs) {
            if (depth.putIfAbsent(direct, 1) == null) {
                queue.add(direct);
            }
        }
        while (!queue.isEmpty()) {
            MavenDependencyKey node = queue.poll();
            for (MavenDependencyKey next : adjacency.getOrDefault(node, List.of())) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(node) + 1);
                    predecessor.put(next, node);
                    queue.add(next);
                }
            }
        }
        Map<MavenDependencyKey, ComplianceGate.Reachability> reachability = new LinkedHashMap<>();
        closure.dependencies().forEach((key, value) -> {
            Integer hops = depth.get(key);
            reachability.put(key, hops == null
                    ? ComplianceGate.Reachability.UNKNOWN
                    : ComplianceGate.Reachability.onBuildGraph(hops, path(key, predecessor, closure.dependencies())));
        });
        return reachability;
    }

    /** The same signal from a declared graph - a CycloneDX document's {@code dependencies} - keyed by {@code bom-ref},
     *  with the same breadth-first walk, so a resolved closure and a published SBOM give one reachability shape. An
     *  unreached component stays absent; a BOM declaring no {@code dependencies} leaves every component
     *  {@link ComplianceGate.Reachability#UNKNOWN}. */
    public static Map<String, ComplianceGate.Reachability> of(DependencyGraph graph) {
        String rootRef = graph.rootRef();
        if (rootRef == null) {
            return Map.of();
        }
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (DependencyEdge edge : graph.edges()) {
            adjacency.computeIfAbsent(edge.from(), _ -> new ArrayList<>()).add(edge.to());
        }
        Map<String, DependencyComponent> byRef = graph.componentsByRef();
        Map<String, Integer> depth = new LinkedHashMap<>();
        Map<String, String> predecessor = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        depth.put(rootRef, 0);
        queue.add(rootRef);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String next : adjacency.getOrDefault(node, List.of())) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(node) + 1);
                    predecessor.put(next, node);
                    queue.add(next);
                }
            }
        }
        Map<String, ComplianceGate.Reachability> reachability = new LinkedHashMap<>();
        for (DependencyComponent component : graph.dependencies()) {
            Integer hops = depth.get(component.ref());
            if (hops != null) {
                reachability.put(component.ref(), ComplianceGate.Reachability.onBuildGraph(hops,
                        declaredPath(component.ref(), rootRef, predecessor, byRef)));
            }
        }
        return reachability;
    }

    /** The chain of coordinates from the artifact's direct dependency down to {@code ref}; the root is left off, as in
     *  the resolved walk. */
    private static List<String> declaredPath(String ref, String rootRef, Map<String, String> predecessor,
                                             Map<String, DependencyComponent> byRef) {
        Deque<String> chain = new ArrayDeque<>();
        Set<String> guard = new HashSet<>();
        for (String current = ref;
             current != null && !current.equals(rootRef) && guard.add(current);
             current = predecessor.get(current)) {
            chain.addFirst(label(byRef.get(current), current));
        }
        return List.copyOf(chain);
    }

    /** A declared component's path label in the resolved walk's {@code group:artifact:version} shape, else its purl,
     *  else the bare {@code bom-ref} of a component the document never described. */
    private static String label(DependencyComponent component, String ref) {
        if (component == null) {
            return ref;
        }
        return component.group() == null || component.group().isBlank()
                || component.name() == null || component.name().isBlank()
                || component.version() == null || component.version().isBlank()
                ? component.coordinate()
                : component.group().trim() + ":" + component.name().trim() + ":" + component.version().trim();
    }

    private static List<String> path(MavenDependencyKey node,
                                     Map<MavenDependencyKey, MavenDependencyKey> predecessor,
                                     SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) {
        Deque<String> chain = new ArrayDeque<>();
        Set<MavenDependencyKey> guard = new HashSet<>();
        for (MavenDependencyKey current = node; current != null && guard.add(current); current = predecessor.get(current)) {
            chain.addFirst(label(current, dependencies));
        }
        return List.copyOf(chain);
    }

    private static String label(MavenDependencyKey key, SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) {
        MavenDependencyValue value = dependencies.get(key);
        return key.groupId() + ":" + key.artifactId() + (value == null ? "" : ":" + value.version());
    }

    private static MavenDependencyKey keyOf(String coordinate, String prefix) {
        try {
            String suffix = prefix != null && coordinate.startsWith(prefix + "/")
                    ? coordinate.substring(prefix.length() + 1)
                    : coordinate;
            return MavenDependencyKey.parse(suffix).key();
        } catch (RuntimeException _) {
            return null;
        }
    }
}
