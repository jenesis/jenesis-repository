package build.jenesis.repository.dependency;

import module java.base;

/**
 * The dependency graph parsed from one artifact's SBOM: the {@code rootRef} it is about (its
 * {@code metadata.component}), every {@link DependencyComponent} it names and the {@link DependencyEdge}s between them
 * - a plain value, holding no store handle, that a carried bill places as a version's closure.
 *
 * <p>{@link #dependencies()} is the resolved transitive set (every node but the root), the "is X anywhere in this tree"
 * a blast-radius query needs; {@link #directDependencies()} is the root's immediate edges.
 */
public record DependencyGraph(String rootRef, List<DependencyComponent> components, List<DependencyEdge> edges) {

    /** The empty graph an artifact with no embedded SBOM (or an unreadable one) yields. */
    public static final DependencyGraph EMPTY = new DependencyGraph(null, List.of(), List.of());

    public DependencyGraph {
        components = List.copyOf(components);
        edges = List.copyOf(edges);
    }

    /** The graph a manifest declares: {@code root} and one edge from it to each of {@code dependencies} - one level,
     *  since a manifest declares what it depends on directly and nothing of what those depend on in turn. */
    public static DependencyGraph declared(DependencyComponent root, List<DependencyComponent> dependencies) {
        List<DependencyComponent> components = new ArrayList<>();
        components.add(root);
        components.addAll(dependencies);
        List<DependencyEdge> edges = dependencies.stream()
                .map(dependency -> new DependencyEdge(root.ref(), dependency.ref()))
                .toList();
        return new DependencyGraph(root.ref(), components, edges);
    }

    /** Whether the SBOM named no components. */
    public boolean isEmpty() {
        return components.isEmpty();
    }

    /** The components keyed by their document-local {@code bom-ref}, so an edge endpoint resolves to a coordinate. */
    public Map<String, DependencyComponent> componentsByRef() {
        Map<String, DependencyComponent> byRef = new LinkedHashMap<>();
        for (DependencyComponent component : components) {
            byRef.putIfAbsent(component.ref(), component);
        }
        return Collections.unmodifiableMap(byRef);
    }

    /** The component the SBOM is about (its {@code metadata.component}), when the document declared one. */
    public Optional<DependencyComponent> root() {
        return rootRef == null ? Optional.empty() : Optional.ofNullable(componentsByRef().get(rootRef));
    }

    /** The transitive dependency set: every component the SBOM names except the root. CycloneDX records the resolved
     *  closure, so a vulnerable coordinate appearing here puts this artifact in the blast radius. */
    public List<DependencyComponent> dependencies() {
        List<DependencyComponent> result = new ArrayList<>();
        for (DependencyComponent component : components) {
            if (!component.ref().equals(rootRef)) {
                result.add(component);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** The components the root artifact depends on <em>directly</em> (its immediate edges), or empty if unknown. */
    public List<DependencyComponent> directDependencies() {
        return rootRef == null ? List.of() : dependenciesOf(rootRef);
    }

    /** The components {@code ref} depends on directly, resolved through the edges and the component table. */
    public List<DependencyComponent> dependenciesOf(String ref) {
        Map<String, DependencyComponent> byRef = componentsByRef();
        List<DependencyComponent> result = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            if (edge.from().equals(ref)) {
                DependencyComponent target = byRef.get(edge.to());
                if (target != null) {
                    result.add(target);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
