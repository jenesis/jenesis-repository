/**
 * The cache-storage SPI: the {@code CacheStorage} abstraction and the {@code CacheStorageProvider} that
 * discovers a backend with {@code ServiceLoader}, plus the shared {@code Names} rule. Nothing heavier than the
 * java.base-light store contract, so an app or backend builds on it without pulling in a server. A
 * backend ships as its own module that {@code provides} a {@code CacheStorageProvider}: the default filesystem
 * backend, plus the optional s3 and azure backends when on the graph.
 *
 * <p>Both dependencies are taken for the same reason (&sect;2 - shared mechanism has one home and is reused, never
 * copied). {@code build.jenesis.repository.store} carries the shared
 * {@code Providers}/{@code Features} resolution mechanism (SPI hardening plan): this SPI is the cache-side
 * sibling of the artifact store's exclusive-with-default selection, and resolving it through the same primitives
 * is what keeps the two from drifting apart in what they accept and what they refuse.
 * {@code build.jenesis.repository.walk} carries {@code Traversal.Result}, the outcome vocabulary every bounded
 * traversal in this product already answers in, so the cache SPI's enumerations report "did I see everything?" in
 * the words a caller already reads elsewhere - and, crucially, in a type where a truncation without a continuation
 * cursor is unrepresentable. It is {@code transitive} because {@code Traversal.Result} is the return type of three
 * exported methods, so every consumer of this SPI reads it off this module's API.
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

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
