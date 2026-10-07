package build.jenesis.repository.ui.identity;

import module java.base;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.CurrentTenant;

/**
 * The console membership of one tenant, held as <b>grants to principal subjects</b> in the same {@link Authorization}
 * store a minted key's grants live in. A member is {@code Subject.principal("<provider>/<id>")} holding rights at a
 * scope, so a per-tenant {@code EDITOR} and a key scoped to that tenant with {@code repository:write,cache:write} are
 * the same grant, written and matched by the same code.
 *
 * <p>The three console roles are <b>named bundles of that vocabulary</b>: {@link Role#rights} is the bundle a role
 * grants and {@link Role#of} reads a held set back as the strongest role it satisfies. That reading is deliberately
 * monotone rather than an exact inverse, so a grant written through the API still names a role and stays administrable.
 *
 * <p>The id is provider-qualified and stable ({@code github/1024025}, the numeric id rather than the login), so a
 * renamed login inherits nothing and two providers cannot collide; the store encodes it into one segment. The display
 * login is the subject's label.
 *
 * <p>One subject per member makes {@link #find(String)} and the per-request role check point reads, confines write
 * contention to one member, and makes {@link #page} a real bounded page.
 *
 * <p>This identity layer is the module three sign-in modules and the SCIM provisioner plug into without requiring the
 * whole console; {@link ConsoleIdentityConfig} declares it as beans.
 */
public class UserDirectory {

    /** The object whose presence is the tenant, re-exposed as the compile-time constant it is, so a console-plane
     *  module reading this package (key-login's tenant probe, the SCIM guard) need not require the domain module to
     *  name it. */
    public static final String TENANT_FILE = TenantService.TENANT_FILE;

    /** The scope a console role is granted at: everything in the tenant, since the subject already lives under it. */
    private static final String SCOPE = "*";

    /** The most bytes one store key segment may carry - a filesystem maps a segment to a file name. A member's id
     *  becomes a segment, so {@link #requireId} refuses past it rather than letting the store refuse a key the operator
     *  never wrote. */
    private static final int MAX_SEGMENT_BYTES = 255;

    /** A console role: a named bundle of the product's one rights vocabulary. They nest - ADMIN holds everything EDITOR
     *  does, EDITOR everything VIEWER does - so {@link #atLeast} compares ordinals and {@link #of} matches from the
     *  strongest down. */
    public enum Role {

        ADMIN(Authorization.MANAGE_READ, Authorization.MANAGE_WRITE,
                Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE,
                Authorization.CACHE_READ, Authorization.CACHE_WRITE),
        EDITOR(Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE,
                Authorization.CACHE_READ, Authorization.CACHE_WRITE),
        VIEWER(Authorization.REPOSITORY_READ, Authorization.CACHE_READ);

        private final List<String> rights;

        Role(String... rights) {
            this.rights = List.of(rights);
        }

        /** The rights this role grants, as the comma list a grant carries. */
        public String rights() {
            return String.join(",", rights);
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** True when this role is at least as strong as {@code other} (ADMIN > EDITOR > VIEWER). */
        public boolean atLeast(Role other) {
            return ordinal() <= other.ordinal();
        }

        public static Role parse(String token) {
            if ("admin".equalsIgnoreCase(token)) {
                return ADMIN;
            }
            if ("editor".equalsIgnoreCase(token)) {
                return EDITOR;
            }
            return VIEWER;
        }

        /** The strongest role a held set of rights satisfies, or empty - "not a member". Monotone rather than an exact
         *  inverse, so a hand-written grant is a member the console can show: a wildcard or any superset of ADMIN's
         *  bundle reads as ADMIN, anything carrying a write as at least EDITOR, anything carrying a read as VIEWER. */
        public static Optional<Role> of(String granted) {
            if (granted == null || granted.isBlank()) {
                return Optional.empty();
            }
            Set<String> held = new HashSet<>();
            for (String token : granted.split(",")) {
                held.add(token.strip());
            }
            if (held.contains("*")) {                       // the all-privileges token, whatever else is held
                return Optional.of(ADMIN);
            }
            for (Role role : values()) {
                if (held.containsAll(role.rights)) {
                    return Optional.of(role);
                }
            }
            // Rights matching no bundle exactly name the weakest role they are at least as strong as.
            if (held.contains(Authorization.MANAGE_WRITE) || held.contains(Authorization.MANAGE_READ)) {
                return Optional.of(ADMIN);
            }
            if (held.contains(Authorization.REPOSITORY_WRITE) || held.contains(Authorization.CACHE_WRITE)) {
                return Optional.of(EDITOR);
            }
            if (held.contains(Authorization.REPOSITORY_READ) || held.contains(Authorization.CACHE_READ)) {
                return Optional.of(VIEWER);
            }
            return Optional.empty();
        }
    }

    public record User(String id, Role role, String login) {
    }

    private final Authorization authorization;

    /** The tenant these reads and writes are scoped to, resolved per call (the session's selection, or a named
     *  tenant). */
    private final Supplier<String> tenant;

    /** One named tenant's membership, for a caller that knows its tenant: key-login issue and revoke, SCIM
     *  provisioning, a seed. */
    public UserDirectory(Authorization authorization, String tenant) {
        // The cast disambiguates: CurrentTenant is a functional interface too.
        this(authorization, (Supplier<String>) () -> tenant);
    }

    /** The console path: the tenant comes from the current session per request; {@link ConsoleIdentityConfig} declares
     *  it as the shared bean. */
    public UserDirectory(Authorization authorization, CurrentTenant current) {
        this(authorization, (Supplier<String>) current::name);
    }

    private UserDirectory(Authorization authorization, Supplier<String> tenant) {
        this.authorization = authorization;
        this.tenant = tenant;
    }

    /** One bounded page of members and the cursor to resume after - the form every enumerating caller uses. */
    public record Page(List<User> users, Optional<String> nextCursor) {

        public Page {
            users = List.copyOf(users);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    /**
     * One bounded page of members after {@code cursor} ({@code null} starts at the beginning), enumerated over the
     * tenant's member list ({@link Authorization#members}) rather than every principal a group names. Each one's
     * grants are read once, and one that is no member after all is skipped rather than shown role-less: a grant
     * lapsed or torn by a crash, a racing removal, a grant at a narrower scope than the tenant. The page reads on past
     * them until it holds {@code limit} members; it stops after examining {@link #EXAMINED} ids, answering what it
     * found with the cursor to resume after the last one examined.
     */
    public Page page(String cursor, int limit) {
        List<User> users = new ArrayList<>();
        String after = cursor;
        int examined = 0;
        while (true) {
            Authorization.SubjectPage found = authorization.members(name(), after, limit);
            List<String> ids = found.ids();
            for (int i = 0; i < ids.size(); i++) {
                after = ids.get(i);
                examined++;
                read(after).ifPresent(users::add);
                if (users.size() == limit || examined >= EXAMINED) {
                    boolean more = i < ids.size() - 1 || found.next() != null;
                    return page(users, more ? after : null);
                }
            }
            if (found.next() == null) {
                return page(users, null);
            }
            after = found.next();
        }
    }

    /** The most subject ids one {@link #page} examines, members or not. */
    static final int EXAMINED = 2000;

    private static Page page(List<User> users, String next) {
        users.sort(Comparator.comparing(User::id));         // within the page only: a display nicety, page-bounded
        return new Page(users, Optional.ofNullable(next));
    }

    /** One offset-addressed window of members and the tenant's total - the shape SCIM's
     *  {@code startIndex}/{@code count} needs. */
    public record Window(List<User> users, int total) {

        public Window {
            users = List.copyOf(users);
        }
    }

    /**
     * The members at offsets {@code [from, from + count)} of this tenant's stable enumeration and the total member
     * count - SCIM's page and {@code totalResults} in one walk. Ids are enumerated a page at a time and a member's
     * grants are opened only inside the window, so a request holds one page of ids plus the window.
     *
     * <p>The enumeration is the tenant's member list ({@link Authorization#members}), not its principals: a group
     * names people who hold nothing of their own, and a total counted over them answered a syncing IdP with more
     * users than any page of {@code Resources} would ever hold. The list is kept on the write path and repaired at
     * start-up, so the one thing the total can still count that a page leaves out is a member whose grant lapsed or
     * was torn since - a marker the next start takes away.
     *
     * <p>The total is counted rather than kept in a stored counter: a counter beside each membership write drifts
     * across a crash between the writes undetectably, and a {@code totalResults} that silently lies is worse for a
     * syncing IdP than a bounded id walk.
     */
    public Window window(int from, int count) {
        int start = Math.max(0, from);
        // In long, so an IdP probing with count=Integer.MAX_VALUE cannot overflow the bound negative.
        long end = (long) start + Math.max(0, count);
        List<String> wanted = new ArrayList<>();
        int total = 0;
        String cursor = null;
        while (true) {
            Authorization.SubjectPage page = authorization.members(name(), cursor, PAGE);
            for (String id : page.ids()) {
                if (total >= start && total < end) {
                    wanted.add(id);
                }
                total++;
            }
            if (page.next() == null) {
                break;
            }
            cursor = page.next();
        }
        List<User> users = new ArrayList<>(wanted.size());
        for (String id : wanted) {
            read(id).ifPresent(users::add);
        }
        return new Window(users, total);
    }

    /** The page size the offset walk enumerates ids in - an internal step, never a caller's bound. */
    private static final int PAGE = 1000;

    /** One member, by a point read of that subject's own grants - never a read of the tenant's whole membership. */
    public Optional<User> find(String id) {
        return id == null || id.isBlank() ? Optional.empty() : read(id.trim());
    }

    /** The role a tenant grants {@code id}: its own grants together with those its groups confer - the check every
     *  authenticated request makes. Two point reads composed from the id, whatever the tenant's size; reading the own
     *  grants alone would let a group's role reach the repository and never the console. */
    public static Optional<Role> roleIn(Authorization authorization, String tenant, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Authorization.Subject subject = Authorization.Subject.principal(id.trim());
            String own = authorization.grants(tenant, subject).get(SCOPE);
            String derived = authorization.derivedGrants(tenant, subject).get(SCOPE);
            return Role.of(own == null ? derived : derived == null ? own : own + "," + derived);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the membership of " + id + " in " + tenant, e);
        } catch (IllegalArgumentException _) {
            return Optional.empty();   // an id that cannot name a subject names no member either
        }
    }

    /** Grant {@code id} the rights of {@code role} in this tenant, labelled {@code login}. The write is a
     *  compare-and-set inside {@link Authorization}: the member screen, key-login and SCIM write this space from
     *  different requests and replicas. The principal's tenant index is kept by the same write. */
    public void put(String id, Role role, String login) throws IOException {
        Authorization.Subject subject = Authorization.Subject.principal(requireId(id));
        authorization.setGrant(name(), subject, SCOPE, role.rights());
        authorization.setLabel(name(), subject, login);
    }

    public void remove(String id) throws IOException {
        if (id != null && !id.isBlank()) {
            authorization.removeSubject(name(), Authorization.Subject.principal(id.trim()));
        }
    }

    /** Read one member's rights and label, or empty when the subject holds nothing in this tenant. */
    private Optional<User> read(String id) {
        try {
            String tenantName = name();
            Authorization.Subject subject = Authorization.Subject.principal(id);
            return Role.of(authorization.grants(tenantName, subject).get(SCOPE)).map(role ->
                    new User(id, role, label(tenantName, subject)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the membership of " + id, e);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    /** A member's display label, or the empty string - never null, because it is rendered. */
    private String label(String tenantName, Authorization.Subject subject) {
        try {
            return authorization.label(tenantName, subject).orElse("");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the label of " + subject.id(), e);
        }
    }

    /** The tenant this view is bound to, refused rather than guessed when nothing has selected one. */
    private String name() {
        String selected = tenant.get();
        if (selected == null || selected.isBlank()) {
            throw new IllegalStateException("No tenant selected.");
        }
        return selected;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("User id must not be blank.");
        }
        String trimmed = id.trim();
        // Any whitespace is refused, as KeyLoginKeys does: an id reaches a store key, a log line and a page. '=' and
        // ':' are the separators of a properties document and a rights token.
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("=") || trimmed.contains(":")) {
            throw new IllegalArgumentException("User id must not contain '=', ':' or whitespace.");
        }
        // The id becomes one key segment and inherits its byte ceiling: refused here with the limit named, never
        // truncated, which could fuse two members into one subject.
        int encoded = trimmed.replace("%", "%25").replace("/", "%2F")
                .getBytes(StandardCharsets.UTF_8).length;
        if (encoded > MAX_SEGMENT_BYTES) {
            throw new IllegalArgumentException("User id is too long to address: its encoded key segment is "
                    + encoded + " bytes, past the " + MAX_SEGMENT_BYTES
                    + "-byte ceiling a single store key segment may carry.");
        }
        return trimmed;
    }
}
