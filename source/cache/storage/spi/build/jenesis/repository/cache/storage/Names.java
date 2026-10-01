package build.jenesis.repository.cache.storage;

import module java.base;

/**
 * The project and tenant naming rule: alphanumerics and underscores, and for a tenant hyphens too, so a name is a
 * traversal-free path segment and a safe properties key. {@code *}, the all-projects wildcard of a grant file, is never
 * a project. A tenant admits the hyphen as the store-side tenant gates do ({@code [A-Za-z0-9_-]+}), so a tenant valid
 * for artifacts is valid here.
 *
 * <p>The rules compose into the write-path addressability screens - {@link #isEntry}, {@link #isPath} and
 * {@link #isFile} - applied before a caller's coordinate becomes a path or key, the same predicates enumeration uses,
 * so no backend stores an address it would never enumerate, evict or reclaim.
 */
public final class Names {

    public static final String WILDCARD = "*";

    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9_]+");

    /** A tenant additionally admits the hyphen. */
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]+");

    /** The longest addressable relative path, so a hostile name cannot become a key an object store answers with a
     *  protocol error rather than a clean refusal. */
    private static final int MAX_PATH = 1024;

    private Names() {
    }

    public static boolean isProject(String name) {
        return name != null && PROJECT.matcher(name).matches();
    }

    public static boolean isTenant(String name) {
        return name != null && TENANT.matcher(name).matches();
    }

    /** Whether a value is a cache entry's {@code step}/{@code inputs} segment: 1-128 ASCII hex characters. ASCII only,
     *  since {@code Character.digit(c, 16)} would accept Unicode digits into an object key. */
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

    /** Whether {@code entry} addresses a storable blob: a valid {@link #isProject project} and two {@link #isHex hex}
     *  segments, the write-path half of what enumeration accepts, so a write can never land an object its own
     *  enumeration would not see - never counted toward a cap, aged out or reclaimed. */
    public static boolean isEntry(CacheStorage.Entry entry) {
        return entry != null
                && isProject(entry.project())
                && isHex(entry.step())
                && isHex(entry.inputs());
    }

    /** Whether {@code path} addresses a config file in the relative {@code .users/}-style tree: non-empty
     *  slash-separated segments, none {@code .} or {@code ..}, no backslash, no leading or trailing separator. Nesting
     *  is legal; escaping the scope is not. An object store would store a traversal string literally, so the predicate
     *  refuses it up front. */
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

    /** Whether {@code file} names a file inside a project container: one segment, never a separator, {@code .} or
     *  {@code ..}. */
    public static boolean isFile(String file) {
        return file != null
                && !file.isEmpty()
                && file.length() <= MAX_PATH
                && file.indexOf('/') < 0
                && file.indexOf('\\') < 0
                && !file.equals(".")
                && !file.equals("..")
                // As for a store segment: a control character is a header-splitting or log-forging vector.
                && file.chars().noneMatch(character -> character < 0x20);
    }
}
