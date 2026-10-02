package build.jenesis.repository.store.filesystem;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The {@code filesystem} provider: a store rooted at {@code jenrepo.filesystem.root}, which is <em>required</em>.
 *
 * <p>There is no default root: on a host a default path is presumptuous, and in a container it is the writable layer,
 * so an unconfigured repository would discard itself on {@code docker rm}. Because
 * {@code Providers.exclusiveWithDefault} validates the chosen provider whether it was selected or fell back,
 * {@code filesystem} stays the default choice and an unconfigured deployment fails naming the key to set.
 */
public final class FilesystemArtifactStoreProvider implements ArtifactStoreProvider {

    /** How a write reaches the disk: {@code strict}, the default, forces each file and the rename that places it before
     *  the write answers, so an acknowledged write survives a power loss; {@code relaxed} renames atomically and leaves
     *  the flush to the operating system, so a power loss can roll back the last few seconds of writes but never tear
     *  one. Strict costs a synchronous flush or two per write - a fraction of a millisecond on a disk that protects its
     *  cache, ten or more on one that does not; relaxed is for a development machine or a test lane. */
    public static final String DURABILITY_KEY = "jenrepo.filesystem.durability";

    /** The value of {@link #DURABILITY_KEY} a deployment gets when it sets none. */
    public static final String DURABILITY_DEFAULT = "strict";

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public String where() {
        return "on the filesystem";
    }

    /** The store root. Required: a store that guesses where to put bytes loses them. */
    @Override
    public Set<String> requiredConfig() {
        return Set.of("jenrepo.filesystem.root");
    }

    /** The root and the durability: everything this backend reads. */
    @Override
    public Set<String> config() {
        return Set.of("jenrepo.filesystem.root", DURABILITY_KEY);
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        // Never null or blank: requiredConfig() is validated before this is called.
        Path path = Path.of(config.apply("jenrepo.filesystem.root"));
        try {
            // The root is created owner-only (rwx------), and a root that cannot be created fails fast.
            OwnerOnly.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create filesystem store root " + path, e);
        }
        // A lookup that names no durability falls back to the JVM's system property, so a JVM started relaxed opens
        // every store relaxed.
        String durability = config.apply(DURABILITY_KEY);
        return new FilesystemArtifactStore(path,
                durable(durability != null ? durability : System.getProperty(DURABILITY_KEY)));
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
