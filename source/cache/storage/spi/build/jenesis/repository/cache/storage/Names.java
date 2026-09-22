package build.jenesis.repository.cache.storage;

import module java.base;

/**
 * Project and tenant naming rule, shared by the dispatcher and the ui: alphanumerics and underscores
 * (and, for a tenant, hyphens), so a name is a trivial, traversal-free path segment and a safe properties
 * key. {@code *} (the all-projects wildcard in a grant file) is therefore never a real project name.
 *
 * <p>A tenant is the top-level container a project lives under and must be an equally safe path segment,
 * but it admits the hyphen the store-side tenant gates ({@code StoreTenants}, the
 * {@code Repositories}) already accept ({@code [A-Za-z0-9_-]+}), so a hyphenated tenant like
 * {@code acme-corp} that works for artifacts is also valid for SCIM, key-login and console membership -
 * the three tenant-name gates stay in sync rather than splitting on the hyphen. The hyphen is
 * traversal-safe (it is neither {@code .} nor a path separator) and a safe properties key, so it changes
 * nothing about the scope or key-space guarantees; a project keeps the stricter underscore-only rule.
 *
 * <p>The same rules also compose into the SPI's write-path <em>addressability</em> screens - {@link #isEntry},
 * {@link #isPath} and {@link #isFile} - which every backend applies before it turns a caller's coordinate into a
 * path or an object key. They are not new policy: {@link #isHex} was already what all four backends use to decide
 * what counts as an entry when they <em>enumerate</em>, and what the dispatcher screens a request with. Stating them
 * once here, on the write path too, is what stops a filesystem backend refusing an address that an object store
 * stores literally at a key nothing will ever enumerate, evict or reclaim.
 */
public final class Names {

    public static final String WILDCARD = "*";

    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9_]+");

    /** A tenant additionally admits the hyphen, matching the store-side tenant gates ({@code [A-Za-z0-9_-]+}). */
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]+");

    /** The longest addressable relative path. Bounded so a hostile name cannot be grown into a key an object store
     *  answers with a protocol error instead of a clean refusal. */
    private static final int MAX_PATH = 1024;

    private Names() {
    }

    public static boolean isProject(String name) {
        return name != null && PROJECT.matcher(name).matches();
    }

    public static boolean isTenant(String name) {
        return name != null && TENANT.matcher(name).matches();
    }

    /** Whether a value has the shape of a cache entry's {@code step}/{@code inputs} segment: 1-128 ASCII hex
     *  characters. The one shared predicate for the dispatcher and every storage backend - and deliberately
     *  ASCII-only, where {@code Character.digit(c, 16)} would also accept Unicode digits and fullwidth letters
     *  into what becomes an object key. */
    public static boolean isHex(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code entry} addresses a storable cache blob: a valid {@link #isProject project} container and the two
     * {@link #isHex hex} segments the record documents. This is the <em>write-path</em> half of a predicate every
     * backend already applied on the read path - all four skip a {@code <step>/<inputs>} pair that is not hex when
     * they enumerate - so an unscreened write could land an object its own enumeration would never see again: never
     * counted toward a project's size cap, never aged out by the ttl, never reclaimed by the free-space sweep. The
     * dispatcher screens exactly this before it builds an {@link CacheStorage.Entry}; stating it here is what makes
     * the four backends refuse the same publish the same way instead of one refusing, one silently storing nothing
     * and two storing an unreclaimable object at a traversal-shaped key.
     */
    public static boolean isEntry(CacheStorage.Entry entry) {
        return entry != null
                && isProject(entry.project())
                && isHex(entry.step())
                && isHex(entry.inputs());
    }

    /**
     * Whether {@code path} addresses a config file in the relative {@code .users/}-style tree: a non-empty,
     * slash-separated sequence of non-empty segments, none of which is {@code .} or {@code ..}, with no backslash and
     * no leading or trailing separator. Nesting is legal ({@code .users/<hash>/projects.properties}); escaping the
     * scope is not. A filesystem backend resolves and confines the path, so it already refuses a traversal; an object
     * store treats the same string as an opaque key and would store it literally, which is the divergence this
     * predicate removes.
     */
    public static boolean isPath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH) {
            return false;
        }
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code file} names a file <em>inside</em> a project container: one segment, never a separator and
     *  never {@code .} or {@code ..}. The project-config methods take the container and the file name separately, so
     *  a file name carrying a separator is an escape attempt rather than a nested path. */
    public static boolean isFile(String file) {
        return file != null
                && !file.isEmpty()
                && file.length() <= MAX_PATH
                && file.indexOf('/') < 0
                && file.indexOf('\\') < 0
                && !file.equals(".")
                && !file.equals("..")
                // The store SPI's rule for a segment, which this used to state without: a CR or LF in a name is a
                // header-splitting or log-forging vector before it is anything else, and a name that the store's
                // own safeSegment would refuse must not be accepted one layer up.
                && file.chars().noneMatch(character -> character < 0x20);
    }
}
