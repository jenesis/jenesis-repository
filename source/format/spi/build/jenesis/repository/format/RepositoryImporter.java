package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The optional migration-import capability of a {@link RepositoryFormat}: a format that also implements this can
 * absorb one ecosystem's artifacts from a foreign repository manager (Nexus, Artifactory) into the content-addressed
 * store, so a deployment can migrate off an incumbent. This is the fourth {@code instanceof} capability on the one
 * discovered format seam - the shape {@link ProxyFormat} ("can pull through"), {@link ArtifactLayout} ("can describe
 * coordinates") and the downstream blob layout already have - not a second discovered service: an importer is always
 * the migration write-half of its format, built on the same publish primitives, shipped in the same module. The
 * orchestrator discovers formats with {@link java.util.ServiceLoader} and filters them by {@code instanceof
 * RepositoryImporter}; a source connector enumerates every asset of a source repository and each is routed to the
 * format that {@link #imports} its source format, so the migration coverage is simply the set of importing formats on
 * the module path. The core ships the Maven, Docker (OCI) and raw formats with this capability; another format adds it
 * by implementing this interface, and an asset whose source format no installed format imports is skipped. An import
 * writes through the store and the format's own publish primitives, so the imported repository regenerates its own
 * indexes and metadata rather than copying the source's.
 *
 * <p>Both methods are named to avoid an erasure clash with the format seam a format already implements:
 * {@link #imports} rather than {@code handles} (which {@link RepositoryFormat#handles(String)} owns for request-path
 * claiming) and {@link #importTarget} rather than {@code describe} (which {@link ArtifactLayout#describe(String)} owns
 * for the coordinate behind a request path) - so one format object can carry the layout and the importer capability
 * at once. There is no backwards-compatibility constraint; the re-pin batch absorbs the rename.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link RepositoryFormat}: that contract still binds, and the clauses below state what
 * absorbing a foreign repository's assets adds. {@code ImportContract} in the importer testkit proves the read half
 * (the {@code ImportSource} the walk comes from) and {@code ImporterContractTest} proves this half over every
 * discovered importer.
 * <ol>
 * <li><b>Thread-safety.</b> The importer is the format singleton, and the orchestrator may walk several assets
 *     concurrently, so both methods are safe to call concurrently. Neither may keep per-asset state on the instance.</li>
 * <li><b>Idempotency / replay.</b> An import is resumable and therefore replayed: {@link #importArtifact} of the same
 *     path and bytes twice must converge on the same stored state, never duplicate it. The content-addressed blob makes
 *     the body idempotent; a format's own sidecars and pointers must be written the same way (compare-and-set, or a
 *     write whose second application is a no-op).</li>
 * <li><b>Absence sentinel.</b> {@link #importTarget} answers {@link Optional#empty()} for an asset this format lays out
 *     <em>without</em> an edge screen, and never {@code null}. Empty is a positive declaration ("my own choke point
 *     screens this"), not a way to decline an asset: the edge reads it as permission to stream the source bytes
 *     straight through, so an importer that cannot lay an asset out must refuse it rather than answer empty.</li>
 * <li><b>Traversal refusal.</b> A source path is as client-supplied as a request path - it derives from a name someone
 *     published to the incumbent - so a path that is not {@link #importable} is refused by <em>both</em> methods with an
 *     {@link IllegalArgumentException} naming it. It is never echoed into the descriptor {@link #importTarget} returns:
 *     that descriptor's path is what the import edge screens against and what an edition records for a held or rejected
 *     asset, and its store key is what a quarantine diversion is composed from, so a traversal-shaped path there aims
 *     the screen and the record at a coordinate the asset will never occupy. The read half is contract-bound never to
 *     report such a path ({@code ImportSource.safePath}); this is the belt behind that brace, and it is the one
 *     {@link ArtifactLayout#addressable} screen the coordinate seam already uses rather than a second definition.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #importArtifact} copies its stream straight to storage; an artifact is never
 *     materialised. An importer that must parse a coordinate or a manifest out of the content may buffer it only under
 *     an explicit cap (the OCI manifest limit is the reference), never a whole artifact.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #imports} and {@link #importTarget} derive from their arguments alone - no
 *     store read, no network - so the edge can call {@link #importTarget} before it has spent a byte of bandwidth on
 *     the asset.</li>
 * <li><b>Error visibility (&sect;9).</b> {@link #importArtifact} throws rather than swallowing: an asset that could not
 *     be laid out must not be counted as imported. Only a refusal the format itself renders (an unparseable OCI
 *     manifest) may be logged and skipped, and only because nothing was laid out.</li>
 * <li><b>Lifecycle / ownership.</b> The importer is the {@code ServiceLoader}-discovered format instance itself; the
 *     orchestrator creates nothing and closes nothing. It owns no threads and no clients. The caller opens and closes
 *     the content stream {@link #importArtifact} is handed.</li>
 * <li><b>Ordering / concurrency.</b> Assets arrive in the source's enumeration order and the importer may not depend on
 *     any other order - in particular, a referent may arrive before or after its referrer, so an importer that links
 *     one asset to another lays out what it has and converges on the rest.</li>
 * <li><b>Durability / delivery.</b> The commit point is the format's own serving-pointer link inside
 *     {@link #importArtifact}, which the edge calls from within {@code Publication.commit}'s accepted-layout callback -
 *     so the screen has already run and the blob is already stored when the pointer lands (pointer-last). A crash
 *     before the pointer leaves an unreferenced blob the garbage collector reclaims; a crash after it leaves an
 *     imported asset a resumed walk re-imports idempotently.</li>
 * </ol>
 */
public interface RepositoryImporter {

    /**
     * Whether a source path may be laid out at all: relative (a leading slash is the Nexus 3.71 shape and is dropped),
     * non-empty, and every segment a single addressable name - no empty, {@code .} or {@code ..} segment, no separator
     * smuggled inside one, no backslash.
     *
     * <p>Deliberately {@link ArtifactLayout#addressable} applied segment by segment rather than a second screen: the
     * coordinate seam already refuses exactly these shapes through that predicate, and a source path is the same kind
     * of semi-trusted, client-supplied name one segment at a time. Stating it here means a new importer inherits the
     * guard instead of being the next one to compose {@code "/raw/" + "../x"} (&sect;13).
     */
    static boolean importable(String sourcePath) {
        if (sourcePath == null) {
            return false;
        }
        String relative = sourcePath.startsWith("/") ? sourcePath.substring(1) : sourcePath;
        return !relative.isEmpty() && ArtifactLayout.addressable(relative.split("/", -1));
    }

    /** The screen behind clause 4, applied by every importer at both seams: a source path that is not
     *  {@link #importable} is refused by name rather than echoed into a descriptor or a store key. */
    static String importablePath(String sourcePath, String format) {
        if (!importable(sourcePath)) {
            throw new IllegalArgumentException("the " + format + " importer refuses the source path '" + sourcePath
                    + "': a segment is empty, '.', '..' or carries a separator, so it addresses nothing this format "
                    + "can lay out. An import source must not report such a path (ImportSource.safePath).");
        }
        return sourcePath.startsWith("/") ? sourcePath.substring(1) : sourcePath;
    }

    /** Whether this format can import a source repository of the given format - the source manager's name, e.g.
     *  {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}. Named
     *  {@code imports} rather than {@code handles} so it does not collide with {@link RepositoryFormat#handles(String)}
     *  (same erasure) on a format that carries both. */
    boolean imports(String sourceFormat);

    /** The <em>target-layout</em> descriptor the asset at {@code sourcePath} will occupy once imported - the coordinate
     *  the import edge screens against, so the gate assesses the real request path an accepted asset serves from
     *  ({@code /maven/<relative>}, {@code /raw/<relative>}) rather than the foreign source path. The edge screens the
     *  asset against this descriptor <em>before</em> handing the accepted body to {@link #importArtifact}; an empty
     *  result marks the asset as one this format lays out without an edge screen (OCI, whose multi-blob manifest
     *  protocol owns its own screening choke point, returns empty), and the edge streams its bytes straight to
     *  {@link #importArtifact} unchanged. Derived from the path only - no content read. Abstract on purpose: every
     *  importing format must decide the coordinate its assets land on, so a demoted layout-only import cannot silently
     *  skip the edge screen. Named {@code importTarget} rather than {@code describe} so it does not collide with
     *  {@link ArtifactLayout#describe(String)} (same erasure) on a layout-aware format that carries both. */
    Optional<ArtifactDescriptor> importTarget(String sourcePath);

    /** Lay one <em>already-screened</em> asset out - its path within the source repository and its content stream -
     *  into the content-addressed store. The content reaching here has already passed the import edge's screen (or is
     *  explicitly unscreenable, when {@link #importTarget} returned empty), so this only lays the bytes out in the format's
     *  namespace: it no longer screens or renders a verdict. On an edge {@code ACCEPT} the stream is the restreamed
     *  {@code blobs/<hash>} the screen stored, not the raw source download. The stream copies straight to storage; an
     *  importer that must inspect the content (to parse a manifest or a coordinate) may read it into a buffer, but a
     *  plain blob streams through unbuffered. The caller closes the stream. */
    void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException;
}
