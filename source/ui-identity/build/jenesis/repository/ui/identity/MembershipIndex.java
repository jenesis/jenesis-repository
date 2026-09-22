package build.jenesis.repository.ui.identity;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.Retries;

/**
 * The reverse user&rarr;tenants index over the root store: a per-user point key holding the set of tenant ids the user
 * is a console member of, so the console's cross-tenant membership read ({@code Memberships.accessibleTo}) is a single read rather than an O(#tenants) walk of every
 * tenant's membership. The forward per-member object stays the source of truth; this index is a derived cache of it, maintained on every membership write and self-healed on read when absent. Tenant
 * deletion is the one write that never rewrites it (a purge removes the tenant's own key spaces, not every member's
 * {@code by-user} entry), so the read side treats a present index as a candidate set and confirms each entry against
 * the forward member object rather than trusting it verbatim.
 *
 * <p>The key is {@code .memberships/by-user/<base64url(user)>.properties} at the root. The dot prefix keeps
 * {@code .memberships} out of {@link build.jenesis.repository.ui.store.TenantService#all() the tenant walk} (a tenant name never
 * begins with a dot), exactly as each tenant's {@code .users/} directory is kept out of the project space; the user id
 * is url-safe-Base64 encoded so a provider-qualified id like {@code github/1024025} becomes one traversal-free path
 * segment. Each member tenant is a property key (value {@code "1"}), so the set is the file's key set.
 *
 * <p><strong>Never fabricate a partial set.</strong> An incremental {@link #add}/{@link #remove} only ever refines an
 * <em>already present</em> index; when the key is absent it is left absent so the read path rebuilds the authoritative
 * full set from the walk. Starting an add from an empty base for an absent key would silently drop the user's other
 * (pre-index) memberships - the exact hazard the reverse index must never introduce. A present index is therefore only
 * ever built by a full walk (the read-path {@link #stampIfAbsent stamp}) or refined from such a base, so it is always
 * complete; an absent index always triggers a full walk.
 */
public final class MembershipIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(MembershipIndex.class);

    /** The root key space for the reverse index; the dot prefix keeps it out of the tenant walk. */
    private static final String BY_USER = ".memberships/by-user/";

    /** The url-safe, unpadded Base64 encoder that turns a provider-qualified id into one safe path segment. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();


    /** The value stored for each member tenant; the set is the property key set, the value only marks membership. */
    private static final String PRESENT = "1";

    private final Documents rootStorage;

    public MembershipIndex(Documents rootStorage) {
        this.rootStorage = rootStorage;
    }

    /** The root key holding {@code user}'s tenant set. */
    public static String keyFor(String user) {
        return BY_USER + ENCODER.encodeToString(user.getBytes(StandardCharsets.UTF_8)) + ".properties";
    }

    /**
     * The tenant set stored for {@code user}, or empty when the index is <em>absent</em> for the user (never built, or a
     * torn write) so the caller falls back to the walk. A present-but-empty index (the user is a member of no tenant)
     * returns a present, empty list - not absent - so a genuinely tenant-less user is not walked on every read.
     */
    public Optional<List<String>> read(String user) {
        String key = keyFor(user);
        if (rootStorage.version(key) == null) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(new TreeSet<>(rootStorage.read(key).stringPropertyNames())));
    }

    /**
     * Record {@code user} as a member of {@code tenant}. Refines an already-present index under a compare-and-set retry;
     * when the index is absent it is left absent (see the class note) so the read-path walk rebuilds the full set.
     */
    public void add(String user, String tenant) throws IOException {
        update(user, set -> set.add(tenant));
    }

    /** Drop {@code tenant} from {@code user}'s set; a no-op when the index is absent (the walk rebuilds without it). */
    public void remove(String user, String tenant) throws IOException {
        update(user, set -> set.remove(tenant));
    }

    private void update(String user, Consumer<Set<String>> edit) throws IOException {
        String key = keyFor(user);
        Retries.compareAndSet("the reverse membership index for " + user, () -> {
            Object token = rootStorage.version(key);
            if (token == null) {
                // Absent: defer to the read-path backfill rather than write a partial set from an empty base.
                return true;
            }
            // Read the token before the body, as UserDirectory#write does, so a write landing in between loses the
            // compare-and-set and retries (the safe direction).
            Set<String> set = new TreeSet<>(rootStorage.read(key).stringPropertyNames());
            edit.accept(set);
            return rootStorage.writeVersioned(key, toProperties(set), token);
        });
    }

    /**
     * Stamp {@code user}'s index from an authoritative walk result, only if it is still absent (create-if-absent, so a
     * write that populated it in between is never clobbered). Best-effort: the read path that calls this has already
     * computed the correct answer from the walk, so a failed stamp only leaves later reads on the walk - it must never
     * fail a login or a render.
     */
    public void stampIfAbsent(String user, Collection<String> tenants) {
        try {
            rootStorage.writeVersioned(keyFor(user), toProperties(tenants), null);
        } catch (IOException e) {
            LOGGER.warn("could not stamp the reverse membership index for " + user
                    + "; reads stay on the O(#tenants) walk until the next write or read stamps it", e);
        }
    }

    private static Properties toProperties(Collection<String> tenants) {
        Properties properties = new Properties();
        for (String tenant : tenants) {
            properties.setProperty(tenant, PRESENT);
        }
        return properties;
    }
}
