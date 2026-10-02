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
 * <p><strong>Why a space of its own.</strong> A list of reserved names would have to be consulted in both directions -
 * refused on creation and excluded from every enumeration that derives tenants from store names - and a place that
 * consulted it in one direction only would offer a product space as a tenant. {@code .system} is a position no user
 * name can reach: a scope name is {@code [A-Za-z0-9_-]+} (see {@link #valid}), which cannot contain a dot. So the
 * separation is a property of the grammar, it holds at every level, and a new product-owned space needs no entry
 * anywhere. A tenant or a repository may legitimately be called {@code audit}, {@code cache} or {@code quota}.
 *
 * <p>{@link #valid} is the shape of a single traversal-free segment, so that a name cannot escape its scope.
 */
public final class Scopes {

    /**
     * The one space the product keeps its own data in, at whatever level that data belongs to. Outside the
     * {@link #valid} grammar on purpose: that is what makes it unreachable by any name a user chooses.
     */
    public static final String SYSTEM = ".system";

    /**
     * The tenant a deployment serves when its configuration names none, and so the first segment of every URL a
     * single-tenant deployment answers: {@code /repository/releases/<repository>/}. The server, the console, the key
     * mint and the command line all fall back to this one definition.
     */
    public static final String DEFAULT_TENANT = "releases";

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
    /** Standing requests for work, at the root: {@code requests/<subject>}, one small object per subject, written
     *  by whatever noticed the need (an unclean shutdown, a contained failure, an operator) and cleared by the
     *  worker that did the work. */
    public static final String REQUESTS = "requests";
    /** Per-node state, at the root: {@code nodes/<id>/running} while a node is up, gone after a clean shutdown. */
    public static final String NODES = "nodes";

    /** The repository's own document, at the root of its scope: which format it holds and when it was created. A
     *  repository exists when it has one. It is not a space under {@link #SYSTEM}: it lives inside the repository,
     *  so the repository is listed like any other the moment it is written, and a leading dot keeps it out of the
     *  names a user chooses. */
    public static final String REPOSITORY = ".repository";

    /**
     * The product's own spaces. An inventory, not a denylist: nothing consults it to decide whether a name is allowed.
     * A surface that must name them - the storage-namespace purge reporting what it cannot reach, a message describing
     * the layout - reads them from here.
     */
    public static final Set<String> SPACES = Set.of(AUTH, CONFIG, AUDIT, LOCKS, CACHE, QUOTA, REQUESTS, NODES);

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

    /** Whether {@code name} is usable as a tenant or repository scope: a single traversal-free segment. */
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
