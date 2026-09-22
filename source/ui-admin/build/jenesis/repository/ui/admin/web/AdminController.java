package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.ScimTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The per-tenant admin screen: manage the current tenant's console members (add/remove viewers,
 * editors and admins, including promoting other admins). The whole screen requires admin in the
 * selected tenant (see the security config): the GET page and the mutating routes alike.
 * Tenant lifecycle and the volume-wide disk reclaim are super-admin concerns and live on the
 * instances screen. Binding names are explicit because the Jenesis javac step does not emit
 * {@code -parameters}.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserDirectory directory;

    private final KnownPrincipals known;
    /** The deployment root, scoped to the session's tenant at each use through {@link #tenantDocuments()}.
     *  Explicitly, rather than by injecting a request-scoped view: see that method for what that cost. */
    private final Documents storage;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;

    /** Only for the fleet-wide half of the cache clear below - the console does not authorize through this. */
    private final Authorization authorization;

    public AdminController(UserDirectory directory, KnownPrincipals known,
                           @Qualifier("rootStorage") Documents storage, AuditTrail audit,
                           CurrentTenant current, ConsoleActor actor, Authorization authorization) {
        this.directory = directory;
        this.known = known;
        this.storage = storage;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
        this.authorization = authorization;
    }

    /** Record a privileged tenant-admin mutation (a SCIM-token or membership change) on the shared audit trail under
     *  the current tenant, attributed to the acting member - the console peer of the /api and domain-layer audit
     *  seams; best-effort, so a failed write never fails the mutation. */
    private void audit(String action, String target) {
        audit.record(current.name(), actor.name(), action, target);
    }

    /** How many members one console page renders. The membership is a key space, one small object per member
     *, so the screen pages it rather than reading a tenant's whole directory to draw a table. */
    private static final int MEMBERS_PAGE = 200;

    /** How many seen principals the id field offers. A suggestion list, not a directory: it is an aid to typing an
     *  id, so it is bounded well below the membership page rather than growing with everyone who ever signed in. */
    private static final int KNOWN_PAGE = 100;

    /** How many groups one console page renders, and how many of each group's members it previews. Both are caps
     *  rather than tuning: the screen costs a page of groups plus one member page each, which is a constant number
     *  of store reads whatever a directory grows to - the same shape the credential listing takes. */
    private static final int GROUPS_PAGE = 50;

    private static final int MEMBER_PREVIEW = 8;

    @GetMapping
    public String admin(@RequestParam(name = "cursor", required = false) String cursor, Model model)
            throws IOException {
        UserDirectory.Page page = directory.page(cursor, MEMBERS_PAGE);
        model.addAttribute("users", page.users());
        model.addAttribute("nextCursor", page.nextCursor().orElse(null));
        // This tenant's groups, read through the same Authorization the API's group routes use.
        model.addAttribute("groups", groups());
        // Everyone this deployment has seen sign in, offered on the id fields. An administrator otherwise has to be
        // told an opaque provider subject out of band, which is the reason sign-in no longer refuses a person the
        // deployment has never seen: the sign-in is what produces the id to grant to.
        model.addAttribute("knownPrincipals", known.page(null, KNOWN_PAGE));
        model.addAttribute("scimConfigured", new ScimTokens(tenantDocuments()).configured());
        model.addAttribute("caches", caches());
        model.addAttribute("node", node());
        return "admin";
    }

    /** One group as this screen renders it: what it grants, and enough of who is in it to recognise. */
    public record GroupRow(String name, Map<String, String> grants, List<String> members, boolean more) {
    }

    /**
     * This tenant's groups, with a preview of each one's membership.
     *
     * <p>Read through the same {@link Authorization} the API's group routes use rather than through a service of
     * this console's own, which is the point rather than a convenience: two surfaces are one capability when they
     * reach one implementation, so a screen written this way closes a parity gap instead of adding one.
     */
    private List<GroupRow> groups() throws IOException {
        List<GroupRow> rows = new ArrayList<>();
        for (String name : authorization.subjects(current.name(), Authorization.Kind.GROUP, null, GROUPS_PAGE).ids()) {
            Authorization.SubjectPage members = authorization.members(current.name(), name, null, MEMBER_PREVIEW);
            rows.add(new GroupRow(name,
                    authorization.grants(current.name(), Authorization.Subject.group(name)),
                    members.ids(), members.next() != null));
        }
        return rows;
    }

    /** Grant a group rights at a scope. Every member holds them from the next request - the write re-derives them
     *  before it returns, which is what makes a group a grant rather than a label.
     *
     *  <p>The action names are the ones the API's group routes record, spelled the same way. They are literals
     *  rather than {@code AuditActions} constants because that class is downstream of the base module and the API's
     *  group routes are IN it - so a shared constant would point the wrong way across the tier boundary, and the
     *  members handlers above already record their peer's names this way for the same reason. */
    @PostMapping("/groups/grant")
    public String grantGroup(@RequestParam("name") String name,
                             @RequestParam("scope") String scope,
                             @RequestParam("tokens") String tokens,
                             RedirectAttributes redirect) throws IOException {
        authorization.setGrant(current.name(), Authorization.Subject.group(name), scope, tokens.trim());
        audit("group.grant.set", name + " " + scope);
        redirect.addFlashAttribute("message",
                "Granted " + tokens.trim() + " on " + scope + " to everyone in " + name + ".");
        return "redirect:/admin";
    }

    @PostMapping("/groups/revoke-grant")
    public String revokeGroupGrant(@RequestParam("name") String name,
                                   @RequestParam("scope") String scope,
                                   RedirectAttributes redirect) throws IOException {
        authorization.removeGrant(current.name(), Authorization.Subject.group(name), scope);
        audit("group.grant.remove", name + " " + scope);
        redirect.addFlashAttribute("message", "Removed the grant on " + scope + " from " + name + ".");
        return "redirect:/admin";
    }

    /** Put a principal in a group. The group need not exist first: one with members and no grants confers nothing,
     *  so there is no order here in which an unmade decision reads as access. */
    @PostMapping("/groups/members")
    public String addGroupMember(@RequestParam("name") String name,
                                 @RequestParam("id") String id,
                                 RedirectAttributes redirect) throws IOException {
        authorization.addMember(current.name(), name, id.trim());
        audit("group.member.add", name + " " + id.trim());
        redirect.addFlashAttribute("message", "Added " + id.trim() + " to " + name + ".");
        return "redirect:/admin";
    }

    @PostMapping("/groups/members/remove")
    public String removeGroupMember(@RequestParam("name") String name,
                                    @RequestParam("id") String id,
                                    RedirectAttributes redirect) throws IOException {
        authorization.removeMember(current.name(), name, id);
        audit("group.member.remove", name + " " + id);
        redirect.addFlashAttribute("message", "Removed " + id + " from " + name + ".");
        return "redirect:/admin";
    }

    /** Delete a group: its grants, its metadata and its membership, with every member re-derived so nothing of it
     *  is left conferring rights. */
    @PostMapping("/groups/remove")
    public String removeGroup(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        authorization.removeSubject(current.name(), Authorization.Subject.group(name));
        audit("group.remove", name);
        redirect.addFlashAttribute("message", "Removed group " + name + " and everything it granted.");
        return "redirect:/admin";
    }

    /** Drop every read cache on the node that served this request, and every node's authorization cache - the
     *  console's reach into the same pair the API's {@code POST /api/admin/caches/clear} and the CLI's
     *  {@code caches clear} make. The listings stay node-local; the grants do not, because the reason to press
     *  this is usually a revoked credential a peer is still honouring. */
    @PostMapping("/caches/clear")
    public String clearCaches(RedirectAttributes redirect) throws IOException {
        int cleared = StoreCache.clearAll();
        boolean grants = authorization.invalidateAcrossNodes();
        audit(AuditActions.CACHES_CLEAR, node() + " (" + cleared + " entries)");
        redirect.addFlashAttribute("message", "Dropped " + cleared + " cached entries on " + node() + "."
                + (grants ? " Every node's authorization cache follows within seconds;" : " This deployment enforces"
                        + " no authorization, so there were no grants to drop;")
                + " their other caches keep their own until each entry's ttl.");
        return "redirect:/admin";
    }

    /** Every read cache on this node, for the screen: name, ttl, hits, misses, entries. */
    public record CacheRow(String name, String ttl, long hits, long misses, int entries) {
    }

    private static List<CacheRow> caches() {
        List<CacheRow> rows = new ArrayList<>();
        for (StoreCache cache : StoreCache.caches()) {
            rows.add(new CacheRow(cache.name(), cache.ttl().toString(), cache.hits(), cache.misses(), cache.size()));
        }
        rows.sort(Comparator.comparing(CacheRow::name));
        return rows;
    }

    private static String node() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException unknown) {
            return "this node";
        }
    }

    /**
     * This session's tenant view of the console's documents.
     *
     * <p>Scoped here rather than injected as a request-scoped bean, and the reason is worth keeping. That bean was
     * a scoped proxy over {@link Documents}, which has no interface - so the proxy had to be a CGLIB subclass, and
     * on the module path that needs the owning package opened to Spring for reflection. Opening a free-core
     * package product-wide to give one constructor parameter a proxy is a bad trade; asking instead for an
     * interface proxy is worse, because it does not fail - it silently supplies something that is NOT the tenant's
     * view, so this controller wrote a tenant's SCIM token at the deployment root while SCIM read the tenant's,
     * and a freshly minted, correct bearer came back 401.
     *
     * <p>One call, naming the tenant it means, has none of that: the boundary is visible at the point it matters.
     */
    private Documents tenantDocuments() {
        String tenant = current.name();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("No tenant selected.");
        }
        return storage.scope(tenant);
    }

    @PostMapping("/scim-token")
    public String setScimToken(RedirectAttributes redirect) throws IOException {
        String token = new ScimTokens(tenantDocuments()).mint();
        audit("scim.token.set", "scim");
        redirect.addFlashAttribute("message",
                "SCIM token (shown once): " + token + " - set it on your identity provider's SCIM connector.");
        return "redirect:/admin";
    }

    @PostMapping("/scim-token/clear")
    public String clearScimToken(RedirectAttributes redirect) throws IOException {
        new ScimTokens(tenantDocuments()).set(null);
        audit("scim.token.clear", "scim");
        redirect.addFlashAttribute("message", "Cleared the SCIM token; SCIM provisioning for this tenant is off.");
        return "redirect:/admin";
    }

    @PostMapping("/users")
    public String addUser(@RequestParam("id") String id,
                          @RequestParam(name = "role", defaultValue = "viewer") String role,
                          @RequestParam(name = "login", required = false) String login,
                          Authentication authentication,
                          RedirectAttributes redirect) throws IOException {
        UserDirectory.Role parsed = UserDirectory.Role.parse(role);
        // Prevent an admin from demoting themselves out of the last admin role: the members screen requires admin, so a
        // tenant left with no admin can no longer manage its own membership. Blocked only when the actor is lowering
        // their OWN role below admin and no other member still holds admin - a super-admin can still recover it, but the
        // tenant would otherwise be locked out. (removeUser already blocks removing your own access outright.)
        if (authentication != null && id.equals(authentication.getName())
                && !parsed.atLeast(UserDirectory.Role.ADMIN) && lastAdmin(id)) {
            throw new IllegalArgumentException("You are the last admin of this tenant. Promote another member to admin "
                    + "before lowering your own role.");
        }
        // A create vs an update, so the trail reads like the SCIM provisioning peer (member.provision / member.update),
        // decided against the existing entry before the upsert.
        boolean existing = directory.find(id).isPresent();
        directory.put(id, parsed, login);
        audit(existing ? "member.update" : "member.provision", id + " " + parsed.label());
        redirect.addFlashAttribute("message", "Saved user " + id + " (" + parsed.label() + ").");
        return "redirect:/admin";
    }

    /** Whether {@code id} is currently an admin of this tenant and no OTHER member holds admin - so demoting them would
     *  empty the tenant's own admin roster. */
    private boolean lastAdmin(String id) {
        if (directory.find(id).map(user -> !user.role().atLeast(UserDirectory.Role.ADMIN)).orElse(true)) {
            return false;
        }
        // An existence probe, so it pages and stops at the first other admin rather than materialising the tenant's
        // whole membership to ask a yes/no question.
        String cursor = null;
        do {
            UserDirectory.Page page = directory.page(cursor, MEMBERS_PAGE);
            for (UserDirectory.User user : page.users()) {
                if (!user.id().equals(id) && user.role().atLeast(UserDirectory.Role.ADMIN)) {
                    return false;
                }
            }
            cursor = page.nextCursor().orElse(null);
        } while (cursor != null);
        return true;
    }

    @PostMapping("/users/remove")
    public String removeUser(@RequestParam("id") String id,
                             Authentication authentication,
                             RedirectAttributes redirect) throws IOException {
        if (authentication != null && id.equals(authentication.getName())) {
            throw new IllegalArgumentException("You cannot remove your own access.");
        }
        directory.remove(id);
        audit("member.deprovision", id);
        redirect.addFlashAttribute("message", "Removed user " + id + ".");
        return "redirect:/admin";
    }
}
