package build.jenesis.repository.store.filesystem;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The {@code filesystem} provider: a store rooted at {@code jenrepo.filesystem.root}, which is <em>required</em>.
 *
 * <p>There is no default root. Storage is the setting a wrong guess LOSES data over rather than merely
 * misconfigures: on a host a default path is presumptuous, and in a container it is the writable layer, so an
 * operator who configured nothing would get a working repository that discarded itself on {@code docker rm}. Every
 * other backend refuses too - S3 names its bucket in {@link #requiredConfig()} and fails loudly.
 *
 * <p>Declaring the root required is all it takes, because {@code Providers.exclusiveWithDefault} validates the
 * CHOSEN provider whether it was selected or fell back. So {@code filesystem} stays the default <em>choice</em>
 * and cannot be a silent one: an unconfigured deployment fails naming the key to set.
 */
public final class FilesystemArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "filesystem";
    }

    /** The store root. Required: a store that guesses where to put bytes is a store that loses them. */
    @Override
    public Set<String> requiredConfig() {
        return Set.of("jenrepo.filesystem.root");
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        // Never null or blank: requiredConfig() above is validated before this is called.
        Path path = Path.of(config.apply("jenrepo.filesystem.root"));
        try {
            // Create the store root owner-only (rwx------) up front, so the top-level container is never left
            // world-readable at the process umask; a root that cannot be created is a fail-fast, not a store
            // that silently lands blobs somewhere unintended.
            OwnerOnly.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create filesystem store root " + path, e);
        }
        return new FilesystemArtifactStore(path);
    }
}
