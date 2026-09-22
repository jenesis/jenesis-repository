package build.jenesis.repository.format.debian.keys;

/**
 * Where a repository keeps the keyring it verifies Debian packages against, and the cache namespace it is read
 * through.
 *
 * <p>Both halves of that arrangement have to spell the key the same way: the format writes the keyring when an
 * operator uploads one, and whatever verifies a signature reads it back. So it is shared vocabulary rather than
 * either side's detail, and it sits in a package of its own - exported to anything on the module path - so that
 * agreeing on it costs no access to the format's implementation.
 *
 * <p>Note for anyone moving one of these: javac inlines a {@code static final String} into every reader, so a
 * changed value reaches a module only when that module is recompiled. They are values a store already holds
 * under the old spelling, so changing one is a migration rather than an edit.
 */
public final class DebianKeyring {

    /** The store key the trusted keyring is written to, under the repository's own blob space. */
    public static final String KEY = "debian/keyring/trusted.asc";

    /** The {@code StoreCache} namespace {@link #KEY} is read through, so an upload can invalidate the read. */
    public static final String CACHE = "debiankeyring";

    private DebianKeyring() {
    }
}
