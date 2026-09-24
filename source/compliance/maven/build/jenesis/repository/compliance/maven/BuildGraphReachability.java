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
 * Build-graph reachability over a resolved Maven closure: for every resolved dependency, the shortest dependency
 * path from the artifact to it, so a compliance finding about a transitive dependency can be marked with whether -
 * and how directly - the vulnerable component is actually reachable on the build graph (a direct dependency, a
 * deep transitive one, or - defensively - not connected at all). This is the "reachable on the build graph"
 * signal after-the-fact SCA scanners can only approximate; here it is read straight off the closure the
 * publishing gate already resolves, so it costs no extra resolution.
 *
 * <p>The graph is reconstructed from {@link MavenResolver.Closure#edges()}: an edge with a {@code null} parent is a
 * direct dependency of the artifact (depth one), every other edge is a {@code parent -> child} relationship. Edge
 * coordinates carry the version declared at that edge, but a resolved closure collapses each coordinate to a single
 * version, so nodes are matched on their {@link MavenDependencyKey} (group/artifact/type/classifier) and the
 * resolved version is read back from the closure for the human-readable path. A breadth-first walk from the direct
 * dependencies yields each node's shortest depth and path; a resolved dependency the walk never reaches (which a
 * well-formed compile closure does not produce, but a partially-resolved tree can) stays {@link
 * ComplianceGate.Reachability#UNKNOWN} rather than being asserted reachable.</p>
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

    /**
     * The same signal read off a <em>declared</em> graph - the {@code dependencies} relationships a CycloneDX document
     * already records - keyed by the component's {@code bom-ref}. The walk is identical (breadth-first from the root's
     * direct edges, shortest depth and the path that reached it), which is the point: a closure resolved over the
     * network and one read out of a published SBOM produce the same reachability shape, so a finding's "how directly
     * is this reachable" reads the same whichever source answered.
     *
     * <p>Unreached components stay absent rather than being asserted reachable - a BOM that names components but
     * declares no {@code dependencies} block gives every one of them {@link ComplianceGate.Reachability#UNKNOWN},
     * which is the honest answer for a document that lists a closure without saying how it hangs together.
     */
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

    /** The chain of coordinates from the artifact's direct dependency down to {@code ref}, labelled with each
     *  component's own coordinate. The root itself is left off, matching the resolved-closure walk above, whose paths
     *  begin at the direct dependency rather than repeating the artifact being published. */
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

    /** A declared component's path label in the SAME shape the resolved walk writes - {@code group:artifact:version} -
     *  so a finding's dependency path reads identically whether the closure came off the network or out of a published
     *  document. Falls back to the component's canonical coordinate (its purl) when the BOM records no split parts,
     *  and to the bare {@code bom-ref} when the edge points at a component the document never described. */
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
