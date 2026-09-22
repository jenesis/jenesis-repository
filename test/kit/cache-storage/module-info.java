/**
 * The cache-storage contract kit: the executable {@code CacheStorage} contract ({@code CacheStorageContract}) and the
 * per-backend registration seam ({@code CacheStorageFixture}) the four {@code CacheStorageProvider} backends run it
 * through. It is the sibling of the {@code build.jenesis.repository.store.testkit}, and it
 * exists for the same reason: {@code test/cache/storage/{s3,gcs,azure}} each hand-wrote their own idea of what a
 * {@code CacheStorage} promises, so the four backends drifted into asserting four different subsets of one
 * interface - and the properties none of them asserted are exactly where they diverged.
 *
 * <p><strong>Test support, never runtime.</strong> Nothing here provides a service and nothing here is reachable from
 * a request path, so the module is inert on a runtime graph; no runtime bundle, application shell or plugin module may
 * require it, and {@code CacheStorageTestkitGraphTest} fails the build if a {@code source/**} module ever does. It is
 * a <em>source</em> module rather than a test one because a JUnit test module is a leaf: the contract has to be
 * shared by four fixtures and, later, by any further backend, and a leaf cannot be required.
 *
 * <p><strong>Assertion-library-free and JUnit-free.</strong> The kit holds no {@code @Test} body - the JUnit driver
 * lives under {@code test/cache/storage/contract}, exactly as the store splits {@code source/store/testkit} from
 * {@code test/store/contract}. A failed check is a plain {@link java.lang.AssertionError} naming the backend, the
 * property and the expectation, so the module needs nothing beyond {@code java.base} and the cache-storage SPI.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.storage.testkit {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.cache.storage.delegating;
    // Permitted since the kit moved under test/: the JUnit rule is a direction now, not a ban on the kits.
    requires org.assertj.core;
    // The contract is stated in terms of the SPI's own types (CacheStorage, Entry, Stored, Names), and every fixture
    // and driver reads them off this module's API, so the dependency is part of that API.
    requires transitive build.jenesis.repository.cache.storage;
    exports build.jenesis.repository.cache.storage.testkit;
}
