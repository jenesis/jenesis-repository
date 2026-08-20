package build.jenesis.repository.store.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;

/**
 * The default filesystem backend, resolved through the SPI over a temporary root - always available, so this fixture
 * is the one leg of the contract suite that runs on every machine and in every lane, Docker or not.
 */
final class FilesystemStoreFixture implements StoreFixture {

    private Path root;
    private ArtifactStore store;

    /** The one backend with no endpoint at all: a local directory has no transport to screen. The property is proven
     *  by the three backends that do have one ({@code s3}, {@code gcs}, {@code azure-blob}), each of which reaches its
     *  emulator over plaintext http through the explicit opt-out. */
    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return Map.of(StoreContract.Property.PLAINTEXT_ENDPOINT_REFUSED,
                "the filesystem backend is a directory on the host and has no endpoint, so there is no transport "
                        + "scheme to refuse; the property is proven by the s3, gcs and azure-blob fixtures, which each "
                        + "reach their emulator over plaintext http through the explicit opt-out");
    }

    @Override
    public String backend() {
        return "filesystem";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider";
    }

    @Override
    public void start() throws IOException {
        root = Files.createTempDirectory("store-contract-filesystem-");
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null).scope(Containers.uniqueScope());
    }

    @Override
    public ArtifactStore store() {
        return store;
    }

    @Override
    public void close() throws IOException {
        if (root == null) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
