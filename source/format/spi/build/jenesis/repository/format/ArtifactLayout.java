package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

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
 * <p><b>An empty {@link #paths} is an answer, not a gap</b>, and a whole format family gives it. A layout in this
 * interface's sense lays its artifacts out <em>under the published tree</em>, where a version owns a directory of its
 * own - which is why the reverse mapping can be a handful of <em>directory prefixes</em> that a caller then
 * enumerates. A format that keeps its artifacts in a blobs namespace of its own has no such directory: npm, RubyGems,
 * Debian and Go all place every version of a package in one folder beside its siblings, so a prefix would name the
 * package rather than the version, and enumerating it would hand an eviction the neighbours. Those formats therefore
 * answer empty from both overloads here and map a coordinate through their own namespace's layout contract instead
 * (the one {@link EcosystemLayout} names), which answers with the <em>exact</em> pointer keys the version occupies.
 *
 * <p>Both are the same question - "where does this coordinate version live, so it can be unpublished" - and the
 * difference in answer shape is what makes each of them safe: a prefix may be enumerated because the directory
 * belongs to the version alone, and an exact key is required precisely where it does not. So read an empty list here
 * as "this format answers through the other family", never as "this format cannot be evicted"; the second reading is
 * the natural one and it is wrong.
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
public interface ArtifactLayout extends EcosystemLayout {

    /**
     * Whether every one of these coordinate-derived name parts is a single addressable path segment - non-null,
     * non-empty, free of a path separator, and not {@code .} or {@code ..}. The screen {@link #paths} applies before
     * it composes a request path out of a coordinate and a version, so a hostile or malformed coordinate maps to
     * nothing instead of to a traversal-shaped path an eviction would then delete under.
     *
     * <p>It is deliberately the same rule the store screens a key on ({@link ArtifactStore#traversalFree}) plus the
     * {@code /} rule the store screens a <em>scope segment</em> on ({@link ArtifactStore#segment}), stated once here
     * for every layout rather than re-derived per format: a Maven artifactId, an OCI tag and a module name are all
     * single segments, and a Maven groupId is checked component by component because its dots become separators.
     * Shared so a new layout inherits the guard instead of being the next one to forget it (&sect;13).
     *
     * <p><b>It says "the same rule" and now asks for it</b>, rather than restating it. It used to spell out
     * {@code .}, {@code ..} and {@code \} here, which is the same rule only for as long as nobody adds to the
     * original - and something did: the store's screen grew the C0 control characters (D-288), and this copy did not,
     * so a tab-bearing coordinate was addressable here and refused at the key screen. That gap is not merely
     * cosmetic, because the keys {@link #paths} composes are handed to eviction, and a delete is not screened the way
     * a write is: the composing seam is the one that has to refuse. A guard that describes itself as a copy of
     * another is a guard that will fall behind it.
     */
    static boolean addressable(String... parts) {
        for (String part : parts) {
            if (part == null || part.isEmpty() || part.indexOf('/') >= 0 || !ArtifactStore.traversalFree(part)) {
                return false;
            }
        }
        return true;
    }

    /** The package-ecosystem name this format's artifacts report - the value {@link #describe}'s descriptors carry
     *  (a Maven format's OSV name {@code "Maven"}, an npm format's {@code "npm"}) - so a coordinate-only consumer, a
     *  cleanup eviction resolving a stored coordinate back to its format, finds the format by its declared ecosystem
     *  rather than guessing from the format id. Declared on {@link EcosystemLayout}, which the other layout family
     *  shares. */
    @Override
    String ecosystem();

    /** The descriptor for a request path this format owns (hash and size unset, since nothing is stored yet), or empty
     *  when the path carries no coordinate to describe (generated metadata, a directory). Derived from the path only. */
    Optional<ArtifactDescriptor> describe(String path);

    /**
     * {@link #describe(String)} against the repository the path belongs to, for a layout whose path-to-coordinate
     * mapping is not a deployment-wide constant.
     *
     * <p>Most layouts are: a Maven path spells its coordinate the same way in every repository on earth, so the pure
     * overload above is exact and this one defaults to it. Some are not. An Ivy repository's layout is a
     * <em>pattern</em> chosen per repository - {@code [organisation]/[module]/[revision]/...} is only Gradle's
     * default - so the same path yields different coordinates, or none, depending on which repository it was
     * addressed to. Without this seam such a layout has two options, and both are bad: fix one pattern for the whole
     * deployment, or answer empty and lose every coordinate-keyed capability (advisory matching, retention, the
     * browse) for the artifacts it serves.
     *
     * <p>{@code store} is already scoped to the tenant and repository, so it <em>is</em> the repository context: a
     * layout reads its own configuration document from it. This is deliberately the same shape
     * {@link #paths(String, String, ArtifactStore)} has beside {@link #paths(String, String)} - the pure form for a
     * caller that has no store and the store form for one that does - and it carries the same obligation: it is a
     * read, never a write or a repair, it is bounded, and a caller on a serving read path uses the pure form. A
     * caller holding a scoped store should prefer this one, because a layout that needs it answers empty from the
     * other and its artifacts then have no coordinates at all.
     */
    default Optional<ArtifactDescriptor> describe(String path, ArtifactStore store) {
        return describe(path);
    }

    /** The request-path directory prefixes a coordinate version occupies across this format's layouts, resolved
     *  against {@code store} so a format can include a cross-published mirror it recorded (a Maven module view found
     *  through the format's own index), so a cleanup pass enumerates and unpublishes every pointer under them from the
     *  coordinate alone - no layout knowledge in the caller. Empty when the coordinate maps nowhere, and empty by
     *  design for a format that serves from a blobs namespace rather than from the published tree - see the class
     *  note above before reading that as a missing mapping. */
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
