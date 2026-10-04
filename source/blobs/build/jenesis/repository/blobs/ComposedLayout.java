package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A blobs-namespace layout whose versions are made of more than the paths they are served at: an OCI image is its
 * manifest, served by tag, and the config, layers and platform manifests it references, each served by digest at a path
 * of its own and shared between images. A copy of a version - another deployment importing this one through its asset
 * listing - lays all of them down, and in an order a client would: what is referenced before what references it.
 *
 * <p>A layout opts in by implementing this. One that does not is a layout whose versions are exactly their
 * {@link #servedPaths served paths}, which is every blobs-namespace format but OCI.
 *
 * <h2>Contract</h2>
 * A role sub-interface of {@link BlobLayout}: that contract still binds, and the clauses below state what this adds.
 * <ol>
 * <li><b>Completeness.</b> {@link #contents} names every path a client of the format needs to have the version, and
 *     ends with the version's {@link #servedPaths served paths}; a referenced object it cannot enumerate is left out
 *     rather than guessed at, and the served paths still answer.</li>
 * <li><b>Order.</b> A path comes after every path it references, so a copy laid down in this order never holds a
 *     manifest whose content is not there yet.</li>
 * <li><b>Absence sentinel.</b> A version with no live served path answers an empty list, never {@code null} and never
 *     an exception.</li>
 * <li><b>Read purity.</b> Pointers and the small documents that say what references what are read; nothing is
 *     written, and no layer or archive body is opened.</li>
 * </ol>
 */
public interface ComposedLayout extends BlobLayout {

    /** Every request path {@code version} of {@code coordinate} is made of, referenced paths first and its served paths
     *  last; empty for a version with nothing served. */
    List<String> contents(String coordinate, String version, ArtifactStore store) throws IOException;
}
