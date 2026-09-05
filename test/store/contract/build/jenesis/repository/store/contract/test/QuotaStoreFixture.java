package build.jenesis.repository.store.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;

/**
 * The quota decorator over the filesystem store, held to the whole contract. A decorator inherits none of the SPI's
 * guarantees for free: it re-implements paging, scanning and the batch around the store it wraps, and one has already
 * been caught paging a level by materialising it. The limit is left unreachable so the contract measures the
 * decorator's faithfulness, not its refusals - those are {@code QuotaArtifactStoreTest}'s.
 */
final class QuotaStoreFixture implements StoreFixture {

    private final FilesystemStoreFixture underlying = new FilesystemStoreFixture();
    private ArtifactStore store;

    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return underlying.unsupported();
    }

    @Override
    public String backend() {
        return "filesystem";
    }

    /** The decorated store's provider: the decorator is not a backend and is declared by no {@code provides} clause. */
    @Override
    public String providerClass() {
        return underlying.providerClass();
    }

    @Override
    public void start() throws IOException {
        underlying.start();
        store = new QuotaArtifactStore(underlying.store(), Long.MAX_VALUE);
    }

    @Override
    public ArtifactStore store() {
        return store;
    }

    @Override
    public void close() throws IOException {
        underlying.close();
    }
}
