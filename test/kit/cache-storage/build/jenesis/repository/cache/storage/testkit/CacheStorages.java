package build.jenesis.repository.cache.storage.testkit;

import module java.base;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorage;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorageProvider;

/**
 * How a test gets a cache storage over a directory, resolved through the SPI exactly as a deployment resolves it.
 *
 * <p>It exists because the alternative is worse in two ways. The storage classes are not constructible from a test:
 * the cache delegates to the artifact store, whose backend modules export no package beyond their own
 * test modules - they are pure service providers, reached through {@code ServiceLoader} and nothing else. And a test
 * that DID reach past the resolution would be exercising a hand-built object rather than the provider wiring a
 * deployment gets, which is the same reason the contract fixtures are required to resolve rather than construct.
 *
 * <p>Every caller was previously spelling {@code new FilesystemStorage(root)}, from thirty-odd places, against a
 * class that no longer exists.
 */
public final class CacheStorages {

    private CacheStorages() {
    }

    /**
     * The console's own document root over a filesystem store rooted at {@code root} - what
     * {@code StorageConfig.rootStorage} builds. It is the repository's store <em>unscoped</em>, because the tenant
     * scopes, the user directory, memberships, SCIM tokens and login keys are console state and live beside the
     * repositories rather than inside the build cache's space.
     *
     * <p>Use this for anything that is not cached build output. {@link #filesystem} is the cache's own storage and
     * sits one space in; a fixture that reaches for it to store a login key writes somewhere nothing reads.
     */
    public static Documents documents(Path root) {
        return Documents.over(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));
    }

    /**
     * A cache storage over a filesystem repository store rooted at {@code root}.
     *
     * <p>The cache has no backend of its own: it delegates to the repository's store and takes one segment inside
     * it. So a fixture configures the STORE - its selection and its root - and the cache follows, which is exactly
     * the property this arrangement exists to have.
     */
    public static CacheStorage filesystem(Path root) {
        String configured = root.toString();
        return CacheStorageProvider.resolve(key -> switch (key) {
            case "jenreg.store" -> "filesystem";
            case "jenreg.filesystem.root" -> configured;
            default -> null;
        });
    }
}
