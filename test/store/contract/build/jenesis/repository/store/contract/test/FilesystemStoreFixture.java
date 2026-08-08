package build.jenesis.repository.store.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.StoreFixture;

/**
 * The default filesystem backend, resolved through the SPI over a temporary root - always available, so this fixture
 * is the one leg of the contract suite that runs on every machine and in every lane, Docker or not.
 */
final class FilesystemStoreFixture implements StoreFixture {

    private Path root;
    private ArtifactStore store;

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
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null).scope(Containers.uniqueScope());
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
