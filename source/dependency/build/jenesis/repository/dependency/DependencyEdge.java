package build.jenesis.repository.dependency;

import module java.base;

/**
 * A directed dependency relationship parsed from a CycloneDX {@code dependencies} entry: the component identified by
 * {@code from} declares a (direct) dependency on the component identified by {@code to}. Both are document-local
 * {@code bom-ref}s that resolve to a {@link DependencyComponent} through {@link DependencyGraph#componentsByRef()}.
 * The full set of edges is the graph shape a reverse-dependency index inverts - "who depends on X" is every edge
 * whose {@code to} resolves to X - while the flat component set is the transitive closure the SBOM already resolved.
 */
public record DependencyEdge(String from, String to) {

    public DependencyEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
