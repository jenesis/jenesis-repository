package build.jenesis.repository.format.debian.keys;

/**
 * Where a repository keeps the keyring it verifies Debian packages against, and the cache namespace it is read through:
 * the format writes it and a verifier reads it back, so both spell it from here. The values are store keys already
 * written, so changing one is a migration; javac inlines them into every reader.
 */
public final class DebianKeyring {

    /** The store key the trusted keyring is written to, under the repository's own blob space. */
    public static final String KEY = "debian/keyring/trusted.asc";

    /** The {@code StoreCache} namespace {@link #KEY} is read through, so an upload can invalidate the read. */
    public static final String CACHE = "debiankeyring";

    private DebianKeyring() {
    }
}
