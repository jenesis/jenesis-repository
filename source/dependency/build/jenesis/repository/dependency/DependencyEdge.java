package build.jenesis.repository.dependency;

import module java.base;

/**
 * A direct dependency parsed from a CycloneDX {@code dependencies} entry: the component {@code from} depends on
 * {@code to}, both document-local {@code bom-ref}s resolved through {@link DependencyGraph#componentsByRef()}; a
 * closure takes each component's distance from the root along them.
 */
public record DependencyEdge(String from, String to) {

    public DependencyEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
