/**
 * The cache-storage interface: {@code CacheStorage} and the shared {@code Names} and {@code Pages} rules, over nothing
 * heavier than the store contract. Its one implementation delegates into the repository's artifact store, so a
 * deployment chooses where cached bytes live by choosing the store, and nothing here is discovered.
 *
 * <p>{@code build.jenesis.repository.walk} carries {@code Traversal.Result}, the
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
}
