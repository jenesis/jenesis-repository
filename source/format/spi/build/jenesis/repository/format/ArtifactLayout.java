package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The optional coordinate/layout capability of a {@link RepositoryFormat}: it maps a request path this format owns to
 * its neutral {@link ArtifactDescriptor} and back, from the path alone - no content read - so read-side concerns
 * (download tracking) and coordinate-only concerns (cleanup eviction) map path to coordinate and back without
 * hand-parsing a layout. Kept off the {@link RepositoryFormat} contract, exactly like {@link ProxyFormat}, so a
 * hosted-only format (raw) that has no coordinates is not forced to implement it; neutral code detects the capability
 * with {@code instanceof}. A format is the single owner of its layout knowledge (the coordinate convention, the
 * prerelease rule, the directory a version occupies), and this is the interface through which it lends that knowledge
 * to the rest of the system.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link RepositoryFormat}: that contract still binds, and the clauses below state
 * what mapping a coordinate to a path and back adds. {@code FormatContract}'s format-seam leg in the format testkit
 * proves them per layout.
 * <ol>
 * <li><b>Thread-safety.</b> Every method is a stateless pure mapping (or, for the store overload, a read) on the
 *     format singleton, safe to call concurrently.</li>
 * <li><b>Absence sentinel.</b> {@link #describe} answers {@link Optional#empty()} for a path that carries no
 *     coordinate (generated metadata, a directory) and {@link #paths} answers an empty list for a coordinate that maps
 *     nowhere; {@code null} is never returned, and neither method throws for an input it cannot map.</li>
 * <li><b>Traversal refusal.</b> A coordinate and a version are as client-supplied as a request path - they arrive from
 *     a published name, an advisory feed or a console form - so a name part that is not a single addressable path
 *     segment ({@link #addressable}) maps <em>nowhere</em>: {@link #paths} answers empty rather than composing a path
 *     carrying a {@code .} or {@code ..} segment. This matters because the paths returned here are handed to eviction,
 *     which unpublishes and deletes under them; a traversal-shaped coordinate must not be able to aim that delete at a
 *     neighbouring key space.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #describe} and {@link #paths(String, String)} derive from the path or the
 *     coordinate <em>alone</em> - no store read, no blob opened - so a read path can call them freely. Only
 *     {@link #paths(String, String, ArtifactStore)} may consult the store, and only to find a mirror the format itself
 *     recorded; it is therefore never called from a serving read path.</li>
 * <li><b>Ordering / determinism.</b> {@link #ecosystem} is a stable constant a coordinate consumer keys on across
 *     editions, and {@link #paths} returns its primary layout path first, in a deterministic order that does not
 *     depend on discovery order or on store enumeration order.</li>
 * <li><b>Bounded work.</b> Both {@link #paths} overloads answer a small, fixed set of directory prefixes - one per
 *     layout view the format publishes - never an enumeration of a version's contents.</li>
 * </ol>
 */
public interface ArtifactLayout {

    /**
     * Whether every one of these coordinate-derived name parts is a single addressable path segment - non-null,
     * non-empty, free of a path separator, and not {@code .} or {@code ..}. The screen {@link #paths} applies before
     * it composes a request path out of a coordinate and a version, so a hostile or malformed coordinate maps to
     * nothing instead of to a traversal-shaped path an eviction would then delete under.
     *
     * <p>It is deliberately the same {@code .}/{@code ..} rule the store screens a key on
     * ({@link ArtifactStore#traversalFree}) plus the separator rule the store screens a <em>scope segment</em> on
     * ({@link ArtifactStore#segment}), stated once here for every layout rather than re-derived per format: a Maven
     * artifactId, an OCI tag and a module name are all single segments, and a Maven groupId is checked component by
     * component because its dots become separators. Shared so a new layout inherits the guard instead of being the
     * next one to forget it (&sect;13).
     */
    static boolean addressable(String... parts) {
        for (String part : parts) {
            if (part == null || part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.indexOf('/') >= 0 || part.indexOf('\\') >= 0) {
                return false;
            }
        }
        return true;
    }

    /** The package-ecosystem name this format's artifacts report - the value {@link #describe}'s descriptors carry
     *  (a Maven format's OSV name {@code "Maven"}, an npm format's {@code "npm"}) - so a coordinate-only consumer, a
     *  cleanup eviction resolving a stored coordinate back to its format, finds the format by its declared ecosystem
     *  rather than guessing from the format id. */
    String ecosystem();

    /** The descriptor for a request path this format owns (hash and size unset, since nothing is stored yet), or empty
     *  when the path carries no coordinate to describe (generated metadata, a directory). Derived from the path only. */
    Optional<ArtifactDescriptor> describe(String path);

    /** The request-path directory prefixes a coordinate version occupies across this format's layouts, resolved
     *  against {@code store} so a format can include a cross-published mirror it recorded (a Maven module view found
     *  through the format's own index), so a cleanup pass enumerates and unpublishes every pointer under them from the
     *  coordinate alone - no layout knowledge in the caller. Empty when the coordinate maps nowhere. */
    List<String> paths(String coordinate, String version, ArtifactStore store);

    /** The request-path folders a coordinate version occupies computed from the coordinate alone - no artifact read,
     *  the primary layout path first. A <em>read path</em> that only needs to navigate to a coordinate's folder (a
     *  console search linking a hit into the browse tree) calls this, never {@link #paths(String, String, ArtifactStore)}
     *  whose store-derived cross-published mirrors may open a stored artifact (Maven reads a jar's module name for its
     *  {@code /module/} mirror) - so the read path never buffers a blob. Defaults to empty, which is exact for a format
     *  whose pointers are not enumerable from the coordinate alone (the shared-{@code blobs} formats already return
     *  empty from the store overload); a format overrides it when its primary folder is a pure function of the
     *  coordinate. */
    default List<String> paths(String coordinate, String version) {
        return List.of();
    }
}
