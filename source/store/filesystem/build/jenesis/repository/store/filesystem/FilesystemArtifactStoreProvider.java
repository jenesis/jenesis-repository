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

    /**
     * How a write reaches the disk: {@code strict}, the default, forces each file and the rename that places it before
     * the write answers, so an acknowledged write survives a power loss; {@code relaxed} renames atomically and leaves
     * the flush to the operating system, so a power loss can roll back the last few seconds of writes but never tear
     * one. Strict costs a synchronous flush or two per write - a fraction of a millisecond on a disk that protects its
     * cache against power loss, ten or more on one that does not - which is what relaxed is for: a development
     * machine, a test lane.
     */
    public static final String DURABILITY_KEY = "jenrepo.filesystem.durability";

    /** The value of {@link #DURABILITY_KEY} a deployment gets when it sets none. */
    public static final String DURABILITY_DEFAULT = "strict";

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
        return new FilesystemArtifactStore(path, durable(config.apply(DURABILITY_KEY)));
    }

    private static boolean durable(String value) {
        String chosen = value == null || value.isBlank() ? DURABILITY_DEFAULT : value.strip().toLowerCase(Locale.ROOT);
        return switch (chosen) {
            case "strict" -> true;
            case "relaxed" -> false;
            default -> throw new IllegalArgumentException(DURABILITY_KEY + "=" + value
                    + " is not a durability this store offers: strict (the default) or relaxed");
        };
    }
}
