package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;

/**
 * Creates the repositories a suite publishes into, over the filesystem store the node under test runs on.
 *
 * <p>A repository holds one format and answers nothing until it is created. The compositions most suites boot carry
 * no surface that creates one - the plain server has neither the console nor the settings API - so the arrangement
 * writes the repository's document, which is the one thing every creation surface writes. A suite that is about the
 * creation surface itself creates through it instead.
 */
public final class TypedRepositories {

    /** The tenant a fixed deployment serves, and a keyless multi-tenant request resolves to. */
    public static final String DEFAULT_TENANT = "default";

    private TypedRepositories() {
    }

    /** Create {@code repository} in the default tenant, holding {@code format}. */
    public static void create(Path root, String repository, String format) {
        create(root, DEFAULT_TENANT, repository, format);
    }

    /**
     * Create {@code repository} in {@code tenant} of the filesystem store at {@code root}, holding {@code format}. A
     * repository that already holds a format is left as it is.
     */
    public static void create(Path root, String tenant, String repository, String format) {
        try {
            new RepositoryDocument(format, Instant.now()).create(ArtifactStoreProvider.resolve("filesystem",
                    key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                    .scope(tenant).scope(repository));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
