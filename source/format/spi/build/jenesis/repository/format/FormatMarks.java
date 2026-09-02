package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

/**
 * The format family's own mark lookup: given a repository's top-level storage namespace, or the ecosystem a browse
 * hit carries, which installed format owns it and what does the console draw for it. The <em>generic</em> half - the
 * neutral fallback, the rendering rule, the generated figure and the three states - is {@link Marks}, shared with
 * every other contributing family; what lives here is only the part that is genuinely the format family's, namely
 * that a format is identified by the request prefix it claims or by the ecosystem it declares.
 *
 * <p>A format writes its layout under its own request prefix ({@code npm} under {@code /npm/}, {@code pypi} under
 * {@code /pypi/}), which is the top-level key its content sits under, so a namespace resolves through
 * {@link RepositoryFormat#handles}. An ecosystem is declared by the coordinate-bearing formats only, so it resolves
 * through {@link EcosystemLayout#ecosystem()} - whichever layout family the format stores through, since a format
 * that keeps its artifacts in a blobs namespace is as installed as one that lays them out under the published tree.
 * Both answer {@link Optional#empty()} when no <em>installed</em> format
 * claims the key, and that emptiness is deliberately not resolved here: a caller looking at a bookkeeping bucket
 * ({@code blobs}, {@code publish}) renders nothing, while a caller looking at a namespace that really holds
 * published artifacts is looking at an <b>orphan</b> - content whose format module is no longer on this deployment -
 * and renders {@link Marks#orphaned}. Only the caller knows which of those it is holding, so only the caller may
 * decide.
 *
 * <p>Discovery is static for the life of the JVM and a mark is a constant in its format's module, so a namespace's
 * or an ecosystem's answer never changes for the process lifetime and each is resolved once and memoized: a
 * repository list renders a mark per repository per namespace and a browse search one per hit, which without this
 * re-scanned the format list and re-decoded the same constant bytes on every row.
 *
 * <p>Presentation only: it maps a format identity to its mark, holds no domain state, reads no store and performs no
 * I/O (&sect;10) - it is called on a render path.
 */
public final class FormatMarks {

    private final List<RepositoryFormat> formats;

    // The resolved marks, memoized per namespace / ecosystem. Optional is the value type because a key no installed
    // format claims is a legitimate, cacheable answer, and a ConcurrentHashMap cannot hold a null.
    private final Map<String, Optional<Mark>> namespaces = new ConcurrentHashMap<>();
    private final Map<String, Optional<Mark>> ecosystems = new ConcurrentHashMap<>();

    /** For a caller that supplies the formats directly (a test, or a component that already holds the resolved set)
     *  rather than through discovery. The list is copied, so the lookup cannot change under a reader mid-page. */
    public FormatMarks(List<RepositoryFormat> formats) {
        this.formats = List.copyOf(formats);
    }

    /** Over every installed, switched-on format ({@link RepositoryFormat#installed()}). Discovery is static per JVM,
     *  so a caller resolves this once and holds it rather than calling it per render. */
    public static FormatMarks installed() {
        return new FormatMarks(RepositoryFormat.installed());
    }

    /**
     * The mark of the format that owns a repository's top-level storage namespace, or empty when no installed format
     * claims it. A format that claims the namespace but declares no mark of its own resolves to its generated figure
     * rather than to empty, so emptiness means exactly one thing: <em>nothing installed here answers for this
     * namespace</em>.
     */
    public Optional<Mark> forNamespace(String namespace) {
        return namespaces.computeIfAbsent(namespace, this::resolveNamespace);
    }

    private Optional<Mark> resolveNamespace(String namespace) {
        String path = "/" + namespace + "/";
        return formats.stream()
                .filter(format -> format.handles(path))
                .findFirst()
                .map(Marks::of);
    }

    /**
     * The mark of the format that declares an ecosystem - a browse search hit carries the ecosystem its coordinate
     * belongs to ({@link EcosystemLayout#ecosystem()}) - or empty when no installed format declares it. As with a
     * namespace, a format that declares the ecosystem but no mark resolves to its generated figure.
     */
    public Optional<Mark> forEcosystem(String ecosystem) {
        return ecosystems.computeIfAbsent(ecosystem, this::resolveEcosystem);
    }

    private Optional<Mark> resolveEcosystem(String ecosystem) {
        return formats.stream()
                .filter(format -> format instanceof EcosystemLayout layout && ecosystem.equals(layout.ecosystem()))
                .findFirst()
                .map(Marks::of);
    }
}
