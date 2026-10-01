/**
 * The cache-storage SPI: {@code CacheStorage}, the {@code CacheStorageProvider} discovered with {@code ServiceLoader},
 * and the shared {@code Names} and {@code Pages} rules, over nothing heavier than the store contract. A backend ships
 * as a module that {@code provides} a {@code CacheStorageProvider}.
 *
 * <p>{@code build.jenesis.repository.store} carries the {@code Providers}/{@code Features} resolution this SPI shares
 * with the artifact store's selection. {@code build.jenesis.repository.walk} carries {@code Traversal.Result}, the
 * outcome of every bounded traversal, in which a truncation without a cursor is unrepresentable; it is
 * {@code transitive} because three exported methods return it.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.storage {
    requires build.jenesis.repository.store;
    requires transitive build.jenesis.repository.walk;
    exports build.jenesis.repository.cache.storage;
    uses build.jenesis.repository.cache.storage.CacheStorageProvider;

    // The family extends IconContributor; transitive, since an implementation overriding icon() names IconResource.
    requires transitive build.jenesis.repository.icon;
}
