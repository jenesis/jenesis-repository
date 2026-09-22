package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.EcosystemLayout;

/**
 * The garbage-collection capability a {@code RepositoryFormat} implements when it stores its artifacts as
 * {@link Blobs} pointers under its own key roots ({@code npm/}, {@code pypi/}, {@code cargo/}, ...): the reference-scan
 * seam, and nothing more. {@link BlobLayout} extends this with the full coordinate-to-pointer mapping that retroactive
 * enforcement needs. A format that implements only {@code BlobRoots} is visible to the reference scan but declares -
 * explicitly, as a type rather than as an empty default - that it wires no coordinate-scoped enforcement; that choice
 * is greppable and reviewed, never an omission that slips in silently.
 *
 * <p><b>The reference-scan seam itself is {@link BlobReferences}, extended rather than restated.</b>
 * {@code blobRoots()} declares the top-level store-key prefixes the format owns, so the garbage-collection sweep reads
 * every pointer beneath them into its reference set - a content blob any live pointer still names is retained, and only
 * a blob no pointer names is reclaimed. Without this a blobs-namespace format's content is invisible to the reference
 * scan, which reclaims (deletes) blobs the format still serves. This is the direct counterpart of Maven's
 * {@code Publication}-namespace path: {@code blobRoots} is to the blobs namespace what the {@code publish/} scan is to
 * Maven. That declaration was once written here <em>and</em> on the collector's own lending seam, which is two
 * homes for one answer and the §13 shape that produces a data-loss bug the day they disagree;
 * {@link BlobReferences} is the home, because the collector that must not get it wrong asks
 * {@link BlobReferences#installed()} for its lenders.
 *
 * <p><b>What extending it buys, beyond one home.</b> Every blobs-namespace format is now a lender the
 * mark phase can ask {@link BlobReferences#references what else a visited key keeps alive}, at zero cost and with no
 * per-format edit: the inherited default answers empty, which is <em>exact</em> for a format whose every served blob is
 * named by a bare-hex pointer body - the scan already counted it. A format whose content is reachable only through a
 * stored document (OCI's config and layer digests live inside the manifest JSON) overrides that default in the
 * repository that owns the document's dialect, and the collector unions the answer without parsing anything.
 *
 * <p>This interface adds one clause of its own to that contract, {@link #ecosystem()}, which the reference-scan
 * seam has no need of: garbage collection is key-keyed throughout, where the eviction and retroactive-enforcement
 * paths arrive holding a {@code Release}'s ecosystem and must find the layout that owns it.
 */
public interface BlobRoots extends BlobReferences, EcosystemLayout {

    /** The OSV / package ecosystem this format's coordinates belong to - the value a {@code Release} carries - so an
     *  eviction finds the owning blob layout from a release's ecosystem alone. Matches the format's
     *  {@code ArtifactLayout.ecosystem()} where it has one, and is the {@link EcosystemLayout} declaration
     *  as well, so a consumer that asks which installed format owns an ecosystem finds a blobs-namespace format the
     *  same way it finds one under the published tree. */
    @Override
    String ecosystem();
}
