package build.jenesis.repository.scope;

import module java.base;

/**
 * The artifact store's key spaces: where the product keeps its own data, and the one rule for a name a user chooses.
 *
 * <p>The store is laid out as {@code <tenant>/<repository>/...}, and the product owns data that is not any user's:
 * credentials, settings, the audit trail, maintenance leases, the build cache, a tenant's usage counter. All of it
 * lives under {@link #SYSTEM} at the level it belongs to - {@code .system/auth/}, {@code .system/audit/} and the
 * rest beside the tenant scopes, {@code .system/quota} inside a tenant beside its repositories.
 *
 * <p><strong>Why a space of its own, and why that name.</strong> These used to sit directly beside the scopes a user
 * names, and nothing in the store could tell the two apart - only a list of forbidden words could, consulted in both
 * directions: refusing a reserved name on the way in (creating a tenant, routing a publish) and excluding one on the
 * way out (every enumeration that derives tenants or repositories from store names). Splitting those two is what
 * once rendered {@code audit} in the console as a tenant with working Open and Delete controls.
 *
 * <p>A list of forbidden words only works while everyone remembers to extend it, and forgetting fails silently - the
 * new space is simply offered as a tenant. Giving the product's data a position of its own removes the question
 * instead of answering it, and {@code .system} is a position no user name can reach: a scope name is
 * {@code [A-Za-z0-9_-]+} (see {@link #valid}), which cannot contain a dot. So the separation is a property of the
 * grammar rather than of anyone's memory, it holds at every level with the same name, and a new product-owned space
 * needs no entry anywhere - it is safe the moment it is written under {@link #SYSTEM}. A tenant or a repository may
 * now legitimately be called {@code audit}, {@code cache} or {@code quota}.
 *
 * <p>{@link #valid} is consequently only what it says: the shape a name must have to be a single traversal-free
 * segment. That still matters - a name carrying a separator or a {@code ..} would escape its scope wherever it sat -
 * but it is no longer what keeps the product's data and a user's data apart.
 */
public final class Scopes {

    /**
     * The one space the product keeps its own data in, at whatever level that data belongs to. Outside the
     * {@link #valid} grammar on purpose: that is what makes it unreachable by any name a user chooses.
     */
    public static final String SYSTEM = ".system";

    /** Credentials, at the root: deployment-wide, because a user spans tenants. */
    public static final String AUTH = "auth";

    /** Settings documents - deployment-global at the root, per-tenant inside a tenant. */
    public static final String CONFIG = "config";

    /** The audit trail, at the root, kept per tenant beneath it. */
    public static final String AUDIT = "audit";

    /** Maintenance leases, at the root. */
    public static final String LOCKS = "locks";

    /** The build cache, at the root: it has no store of its own and takes this space inside the repository's. */
    public static final String CACHE = "cache";

    /** A tenant's usage counter, inside that tenant beside its repositories. */
    public static final String QUOTA = "quota";

    /**
     * The product's own spaces, sorted. An inventory, not a denylist: nothing consults it to decide whether a name
     * is allowed, because nothing has to. It exists so a surface that must name them - the storage-namespace purge
     * reporting what it deliberately cannot reach, an error message describing the layout - reads them from here
     * rather than restating the set.
     */
    public static final Set<String> SPACES = Set.of(AUTH, CONFIG, AUDIT, LOCKS, CACHE, QUOTA);

    /** A traversal-free path segment: the shape any single store scope name must have. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private Scopes() {
    }

    /**
     * The store key prefix of a product-owned {@code space} at the current level, {@code .system/<space>}. Callers
     * addressing one by key compose from this; callers wanting a scoped store take {@link #SYSTEM} and the space as
     * two segments, since a store scope is a single segment by contract.
     */
    public static String space(String space) {
        return SYSTEM + "/" + space;
    }

    /**
     * Whether {@code name} is usable as a tenant or repository scope: a single traversal-free segment. There is no
     * second condition - a well-shaped name is usable, because where the product writes already keeps it apart.
     */
    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * {@code name} trimmed and checked as a tenant or repository scope, or {@link IllegalArgumentException}.
     * {@code what} labels the thing being named ({@code "tenant"}, {@code "repository"}) so the message reads for
     * the surface that raised it.
     */
    public static String require(String what, String name) {
        String trimmed = name == null ? null : name.trim();
        if (!valid(trimmed)) {
            throw new IllegalArgumentException("Invalid " + what + " name '" + name
                    + "': use letters, digits, underscores and hyphens only.");
        }
        return trimmed;
    }
}
