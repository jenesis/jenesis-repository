package build.jenesis.repository.compliance.maven;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;

/**
 * Build-graph reachability: for every dependency of an artifact's graph, the shortest path from the artifact to it, so a
 * finding about a transitive dependency says whether, and how directly, the component is reachable on the build graph.
 * The graph is a published CycloneDX document's, or the closure the gate resolved through the repository an operator
 * named, expressed in the same shape; a breadth-first walk from the root gives each node's shortest depth and path, and
 * one the walk never reaches stays {@link ComplianceGate.Reachability#UNKNOWN}.
 */
public final class BuildGraphReachability {

    private BuildGraphReachability() {
    }

    /** Every dependency of {@code graph} keyed by its {@code bom-ref}, placed on the graph; an unreached component stays
     *  absent, and a graph declaring no edges leaves every component {@link ComplianceGate.Reachability#UNKNOWN}. */
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

    /** A component's path label, {@code group:artifact:version}, else its purl, else the bare {@code bom-ref} of a
     *  component the graph never described. */
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
}
