package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A {@link BlobLayout} whose pointer keys are its served paths: the key a version's file is stored under is the path a
 * client requests it at, without the leading slash. Both directions between a key and a version then follow from
 * {@link #describe} and {@link #blobKeys} alone, which is what this answers - a layout that keeps keys anywhere else
 * implements {@link BlobLayout} and maps them itself.
 */
public interface PathKeyedBlobLayout extends BlobLayout {

    /** The version a pointer key belongs to: the one its served path describes, keyed by the pointer. */
    @Override
    default Optional<ArtifactDescriptor> describePointer(String key) {
        return describe("/" + key)
                .filter(described -> described.coordinate() != null && described.version() != null)
                .map(described -> described.withPath(key));
    }

    /** Every pointer key of the version, as the path it is served at. */
    @Override
    default List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }
}
