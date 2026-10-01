package build.jenesis.repository.dependency;

import module java.base;

/**
 * A direct dependency parsed from a CycloneDX {@code dependencies} entry: the component {@code from} depends on
 * {@code to}, both document-local {@code bom-ref}s resolved through {@link DependencyGraph#componentsByRef()}. A
 * reverse-dependency index inverts the edges: "who depends on X" is every edge whose {@code to} resolves to X.
 */
public record DependencyEdge(String from, String to) {

    public DependencyEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
