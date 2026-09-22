package build.jenesis.repository.dependency;

import module java.base;

/**
 * The dependency graph parsed from one artifact's embedded CycloneDX SBOM: the {@code rootRef} the SBOM is
 * <em>about</em> (its {@code metadata.component}, the published artifact itself), every {@link DependencyComponent}
 * it names, and the {@link DependencyEdge} relationships between them. This is the format-neutral, in-memory model
 * a {@code Lease}-guarded sweep folds into a sharded reverse-dependency index and a "who depends on X" / CVE
 * blast-radius query reads back - deliberately a plain value, holding no store handle and touching no persistence,
 * so it is the same object whether the SBOM came off a filesystem blob, an object-store key or a test fixture.
 *
 * <p>{@link #dependencies()} is the transitive set the SBOM already resolved (every node but the root), the coarse
 * "is X anywhere in this artifact's tree" a blast-radius query needs; {@link #directDependencies()} is the root's
 * immediate edges, the finer "why" behind it.
 */
public record DependencyGraph(String rootRef, List<DependencyComponent> components, List<DependencyEdge> edges) {

    /** The empty graph an artifact with no embedded SBOM (or an unreadable one) yields. */
    public static final DependencyGraph EMPTY = new DependencyGraph(null, List.of(), List.of());

    public DependencyGraph {
        components = List.copyOf(components);
        edges = List.copyOf(edges);
    }

    /** Whether the SBOM named no components (an absent or empty graph carries nothing for an index to record). */
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

    /**
     * The transitive dependency set: every component the SBOM names except the root artifact itself. CycloneDX
     * records the fully resolved closure, so this is the coarse "everything this artifact pulls in" a CVE
     * blast-radius index groups on - if a vulnerable coordinate appears here, this artifact is in the blast radius.
     */
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
