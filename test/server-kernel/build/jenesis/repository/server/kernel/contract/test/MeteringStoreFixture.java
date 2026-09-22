package build.jenesis.repository.server.kernel.contract.test;

import module java.base;

import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The metering decorator over the filesystem store, held to the whole contract: a decorator inherits none of the SPI's
 * guarantees for free, and a timer around each operation must change nothing about what the operation answers.
 */
final class MeteringStoreFixture implements StoreFixture {

    private Path root;
    private ArtifactStore store;

    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return Map.of(StoreContract.Property.PRESIGNED_GET_FETCHES_THE_BYTES,
                "the filesystem store underneath mints no URL: presign answers empty by contract and the caller streams",
                StoreContract.Property.PLAINTEXT_ENDPOINT_REFUSED,
                "the filesystem store underneath is a directory on the host with no endpoint to screen; the property is"
                        + " proven by the object-store legs");
    }

    @Override
    public String backend() {
        return "filesystem";
    }

    /** The decorated store's provider: the decorator is not a backend and is declared by no {@code provides} clause. */
    @Override
    public String providerClass() {
        return "build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider";
    }

    @Override
    public void start() throws IOException {
        root = Files.createTempDirectory("store-contract-metering-");
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        store = new MeteringArtifactStore(filesystem, new SimpleMeterRegistry(), "filesystem")
                .scope("kit" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1));
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
