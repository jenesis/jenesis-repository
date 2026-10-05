package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The walk over each held version's declarations ({@link ClosureResolver}) as the last source the closure pass asks:
 * it serves every ecosystem and always answers - a closure, or {@code UNDECLARED} for a version declaring nothing this
 * repository can read - so a release no earlier source resolves still has one. Its declarations are read with every
 * installed inspector.
 */
public final class DeclaredClosure implements ClosureSource {

    /** The source's name. */
    public static final String NAME = "declarations";

    private final List<QualityInspector> inspectors;

    public DeclaredClosure() {
        this(QualityInspector.all());
    }

    public DeclaredClosure(List<QualityInspector> inspectors) {
        this.inspectors = List.copyOf(inspectors);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> ecosystems() {
        return Ecosystems.canonical();
    }

    @Override
    public Kind kind() {
        return Kind.DECLARATIONS;
    }

    @Override
    public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                    String version, Instant now) throws IOException {
        return Optional.of(new ClosureResolver(walk, inspectors).resolve(ecosystem, coordinate, version, now));
    }
}
