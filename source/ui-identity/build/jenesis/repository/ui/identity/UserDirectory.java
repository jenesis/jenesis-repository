package build.jenesis.repository.ui.identity;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.ui.CurrentTenant;

/**
 * The console membership of one tenant, held as <b>grants to principal subjects</b> in the same
 * {@link Authorization} store a minted key's grants live in.
 *
 * <h2>A person and a key hold rights the same way</h2>
 * A member used to be a small document of its own - {@code role} and {@code login} under the tenant's
 * {@code .users/members/} space - and a key's rights were a grants object under {@code .system/auth}. Two models
 * for one question, kept agreeing by hand, and only one of them was the vocabulary every other surface speaks.
 * A member is now {@code Subject.principal("<provider>/<id>")} holding rights at a scope, so a per-tenant
 * {@code EDITOR} and a key scoped to that tenant with {@code repository:write,cache:write} are the same grant,
 * written the same way and matched by the same code.
 *
 * <p>The three console roles survive as what they always were: <b>named bundles of that one vocabulary</b>.
 * {@link Role#rights} is the bundle a role grants and {@link Role#of} reads a bundle back as the strongest role
 * it satisfies, so the console's authorization matrix goes on asking "is this member at least an EDITOR" while
 * the store holds rights. That derivation is deliberately <em>monotone</em> rather than an exact inverse: a grant
 * written through the API that is not exactly a role's bundle still names the role it is at least as strong as,
 * because a member the console cannot name is a member it cannot administer.
 *
 * <p>The id is provider-qualified and stable ({@code github/1024025}, the numeric id rather than the login) so a
 * renamed login cannot inherit access and two providers cannot collide. It is the subject's id, not a key: the
 * store encodes it into one segment. The display login is the subject's label, which is what that field is for.
 *
 * <h2>One subject per member, which is what makes the enumeration bounded</h2>
 * Every member once lived in a single {@code .users/login.properties}: {@link #page} could not exist,
 * {@link #find(String)} was a whole-document read to answer one lookup, the cross-tenant role check behind every
 * request read it again, and a write re-read it on every lost compare-and-set - so provisioning two
 * <em>unrelated</em> users contended. With a subject per member, {@link #find} and the role check are point
 * reads, a write contends only with a write to the same member, and {@link #page} is a real bounded page.
 *
 * <h2>Its own module</h2>
 * The directory, the reverse index, the super-admin set, the login decision, the starter credential and the
 * console's properties are the identity layer three sign-in modules and the SCIM provisioner plug into. They lived
 * in the admin console's own module until 2026-09-20, so a sign-in module required the whole console - every
 * screen, every Spring surface - to reach five classes, and the console and its sign-in modules could only ever move
 * together. They are Spring-free here bar the two annotations a properties document needs, and
 * {@link ConsoleIdentityConfig} is the one place the console declares them as beans.
 */
public class UserDirectory {

    /**
     * The object whose presence <em>is</em> the tenant, re-exposed from the domain layer as the compile-time
     * constant it is - so a console-plane module that already reads this package (key-login's tenant probe, the
     * SCIM provisioning guard) does not also have to require the domain module to name it.
     *
     * <p>It survives the membership moving into the authorization store. That move changed where a MEMBER lives;
     * it did not change what marks a tenant, and it did not change which modules can see the package that owns
     * the marker.
     */
    public static final String TENANT_FILE = TenantService.TENANT_FILE;

    /** The scope a console role is granted at: everything in the tenant. A member's rights are per-tenant by
     *  construction - the subject lives under the tenant - so the repository scope inside it is the wildcard. */
    private static final String SCOPE = "*";

    /** The most bytes a single store key segment may carry: a filesystem store maps one segment to one file name
     *  and refuses a longer one. A member's id becomes a segment, so {@link #requireId} refuses past it rather
     *  than letting the store refuse a key the operator never wrote. */
    private static final int MAX_SEGMENT_BYTES = 255;

    /**
     * A console role: a named bundle of the product's one rights vocabulary.
     *
     * <p>They nest - an ADMIN holds everything an EDITOR does and an EDITOR everything a VIEWER does - which is
     * what makes {@link #atLeast} a comparison of ordinals and {@link #of} a first match from the strongest down.
     */
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

        /**
         * The strongest role a held set of rights satisfies, or empty when it satisfies none - which is what
         * "not a member" means here.
         *
         * <p>Monotone rather than an exact inverse, deliberately. A grant written through the API need not be one
         * of these three bundles, and a member whose rights the console could not name would be one it could not
         * show or administer. So a wildcard, or any superset of ADMIN's bundle, reads as ADMIN; anything carrying
         * a write reads as at least EDITOR; anything carrying a read reads as VIEWER.
         */
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
            // Held rights that match no bundle exactly: name the weakest role they are at least as strong as, so
            // a hand-written grant is still a member the console can see rather than an invisible one.
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

    /** The tenant these reads and writes are scoped to, resolved at call time (the console path reads the current
     *  session's selection, an off-request caller names it outright). */
    private final Supplier<String> tenant;

    /** The reverse user&rarr;tenants index to keep in step with every write, or {@code null} when this instance
     *  does not maintain it; the read path's backfill covers anything a null leaves behind. */
    private final MembershipIndex index;

    /**
     * A read-only or index-agnostic view of one tenant's membership, with no reverse-index maintenance. Used
     * where the caller does not change membership, or where the read path's backfill rebuilds the index.
     */
    public UserDirectory(Authorization authorization, String tenant) {
        // The cast is load-bearing: CurrentTenant is a functional interface too, so an uncast lambda here is
        // applicable to this class's (CurrentTenant, Documents) constructor as well and the call is ambiguous.
        this(authorization, (Supplier<String>) () -> tenant, null);
    }

    /**
     * The console path: the tenant comes from the current session per request, and every membership write also
     * maintains the reverse user&rarr;tenants index so the console's cross-tenant membership read stays a point
     * read. {@link ConsoleIdentityConfig} declares it as the bean the console and the sign-in modules share.
     */
    public UserDirectory(Authorization authorization, CurrentTenant current, Documents rootStorage) {
        this(authorization, current::name, new MembershipIndex(rootStorage));
    }

    /** An explicit-tenant path off the request thread (key-login issue/revoke, SCIM provisioning), maintaining
     *  the reverse index exactly as the console path does. */
    public UserDirectory(Authorization authorization, String tenant, Documents rootStorage) {
        this(authorization, () -> tenant, new MembershipIndex(rootStorage));
    }

    private UserDirectory(Authorization authorization, Supplier<String> tenant, MembershipIndex index) {
        this.authorization = authorization;
        this.tenant = tenant;
        this.index = index;
    }

    /** One bounded page of members and the cursor to resume after - the form every enumerating caller reaches
     *  for, never a whole-directory one. */
    public record Page(List<User> users, Optional<String> nextCursor) {

        public Page {
            users = List.copyOf(users);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    /** One bounded page of members, resuming strictly after {@code cursor} ({@code null} starts at the
     *  beginning). Each member's grants are read once; a subject whose container exists but holds no rights (a
     *  torn write, or a removal racing this page) is skipped rather than surfaced as a role-less phantom. */
    public Page page(String cursor, int limit) {
        Authorization.SubjectPage found = authorization.subjects(name(), Authorization.Kind.PRINCIPAL, cursor, limit);
        List<User> users = new ArrayList<>(found.ids().size());
        for (String id : found.ids()) {
            read(id).ifPresent(users::add);
        }
        users.sort(Comparator.comparing(User::id));         // within the page only: a display nicety, page-bounded
        return new Page(users, Optional.ofNullable(found.next()));
    }

    /** One offset-addressed window of members and the tenant's total member count - the shape SCIM's
     *  {@code startIndex}/{@code count} needs, which is an offset rather than a cursor. */
    public record Window(List<User> users, int total) {

        public Window {
            users = List.copyOf(users);
        }
    }

    /**
     * The members at offsets {@code [from, from + count)} of this tenant's stable enumeration, and the total
     * number of members - SCIM's {@code startIndex}/{@code count} page plus its required {@code totalResults},
     * answered in one walk.
     *
     * <p><b>Only the window's own members are read.</b> The walk enumerates subject <em>ids</em> a page at a
     * time and opens a member's grants only for an id inside the window - so a request holds one page of ids plus
     * the window, whatever the tenant's user population.
     *
     * <p>The total is counted rather than served from a stored counter, deliberately: a counter maintained beside
     * each membership write drifts across a crash between the two writes with nothing able to detect it, and a
     * {@code totalResults} that silently lies is worse for a syncing IdP than one that costs a bounded id walk.
     */
    public Window window(int from, int count) {
        int start = Math.max(0, from);
        // Computed in long so a caller probing with count=Integer.MAX_VALUE (an IdP does) cannot overflow
        // start+count to a negative bound and silently answer an empty page.
        long end = (long) start + Math.max(0, count);
        List<String> wanted = new ArrayList<>();
        int total = 0;
        String cursor = null;
        while (true) {
            Authorization.SubjectPage page =
                    authorization.subjects(name(), Authorization.Kind.PRINCIPAL, cursor, PAGE);
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

    /**
     * The role a tenant grants {@code id}, read straight from that subject's own grants - the cross-tenant check
     * every authenticated request makes. It is a point read on a key composed from the id, so it costs one small
     * object whatever the tenant's user population.
     */
    public static Optional<Role> roleIn(Authorization authorization, String tenant, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return Role.of(authorization.grants(tenant, Authorization.Subject.principal(id.trim())).get(SCOPE));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the membership of " + id + " in " + tenant, e);
        } catch (IllegalArgumentException _) {
            return Optional.empty();   // an id that cannot name a subject names no member either
        }
    }

    /**
     * Grant {@code id} the rights of {@code role} in this tenant, with {@code login} as its display label.
     *
     * <p>The grant write is a compare-and-set inside {@link Authorization}, which matters here rather than
     * anywhere else: this space is written by the console's member screen, by key-login issue/revoke and by SCIM
     * provisioning, from different requests and different replicas, so nothing local could serialise it.
     */
    public void put(String id, Role role, String login) throws IOException {
        String trimmed = requireId(id);
        Authorization.Subject subject = Authorization.Subject.principal(trimmed);
        authorization.setGrant(name(), subject, SCOPE, role.rights());
        authorization.setLabel(name(), subject, login);
        // The grant is the source of truth; keep the reverse user->tenants index in step. Idempotent: a role or
        // display change on an existing member re-adds the same tenant.
        reindex(trimmed, true);
    }

    public void remove(String id) throws IOException {
        if (id != null && !id.isBlank()) {
            authorization.removeSubject(name(), Authorization.Subject.principal(id.trim()));
        }
        reindex(id, false);
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

    /**
     * Reflect a completed membership write into the reverse index: {@code member} adds this tenant to the user's
     * set, else it is dropped. A no-op unless this instance maintains the index and a valid tenant is bound - the
     * read path's backfill covers a write that did not (or could not) update the index. Runs after the forward
     * write so any concurrent backfill walk already sees the source of truth.
     */
    private void reindex(String id, boolean member) throws IOException {
        if (index == null) {
            return;
        }
        String selected = tenant.get();
        if (selected == null || !Scopes.valid(selected)) {
            return;
        }
        if (member) {
            index.add(id, selected);
        } else {
            index.remove(id, selected);
        }
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("User id must not be blank.");
        }
        String trimmed = id.trim();
        // Reject ANY whitespace, not just a literal space (defence in depth, mirroring KeyLoginKeys.require): an id
        // reaches a store key, a log line and a rendered page, and one carrying whitespace is ambiguous in all
        // three. '=' and ':' go with it: they are the separators a properties document and a rights token use.
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("=") || trimmed.contains(":")) {
            throw new IllegalArgumentException("User id must not contain '=', ':' or whitespace.");
        }
        // The id becomes ONE key segment, so it inherits the store's per-segment byte ceiling. Refuse here, naming
        // the limit, rather than letting a filesystem backend reject the write with a message about a path the
        // operator never wrote - and rather than truncating, which would fuse two members into one subject.
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
