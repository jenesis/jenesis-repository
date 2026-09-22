package build.jenesis.repository.inventory.test;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A minimal PURE blobs-namespace format for the inventory suite - the {@link BlobLayout} counterpart of the
 * {@code publish/}-namespace {@link InventoryTestFormat}. It owns {@code /testblob/<coordinate>/<version>.bin}, serving
 * straight from the shared {@code blobs/} namespace with no {@code publish/} pointer, so the coordinate disclosure face
 * ({@link build.jenesis.repository.inventory.StoreRepositoryInventory#disclosable}) can be exercised over its blobs-namespace
 * leg - a version withheld iff any of its {@link #blobKeys} resolves to a withheld hash - without pulling a real ecosystem
 * module onto the test path. Discovered by the inventory's {@code ServiceLoader}; never serves, so {@link #handle} throws.
 */
public final class InventoryTestBlobFormat implements RepositoryFormat, BlobLayout {

    static final String PREFIX = "/testblob/";
    static final String ECOSYSTEM = "testblob";

    /** The store key a coordinate version's single blob pointer lives at. */
    static String blobKey(String coordinate, String version) {
        return "testblob/" + coordinate + "/" + version + ".bin";
    }

    @Override
    public String name() {
        return "testblob";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the test blob format never serves");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<String> blobRoots() {
        return List.of("testblob");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        String key = blobKey(coordinate, version);
        return store.readVersioned(key).isPresent() ? List.of(key) : List.of();
    }

    /** The single served request path the version occupies - {@code /testblob/<coordinate>/<version>.bin}, the inverse
     *  of {@link #describe} and the served twin of {@link #blobKey}. Present only while the blob pointer is live. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        return store.readVersioned(blobKey(coordinate, version)).isPresent()
                ? List.of(PREFIX + coordinate + "/" + version + ".bin")
                : List.of();
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX) || !path.endsWith(".bin")) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length(), path.length() - ".bin".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, rest.substring(0, slash), rest.substring(slash + 1),
                path, "application/octet-stream", false, null, -1L));
    }
}
